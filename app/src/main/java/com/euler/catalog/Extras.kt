package com.euler.catalog

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Arquivos extras de um app do catálogo (ex.: discos do Resident Evil 4).
 * No catalog.json:
 *   "extras": [ { "url": "...", "pasta": "Resident Evil 4", "nome": "Disco 1.bin" } ]
 * destino "download" (padrão) = Download/<pasta>/<nome> no armazenamento do carro.
 * OBB e pasta data ainda NÃO são tratados aqui (dependem da versão do Android de cada carro).
 */
object Extras {

    data class Extra(val url: String, val pasta: String, val nome: String, val destino: String = "download")

    fun parse(o: JSONObject): List<Extra> {
        val arr = o.optJSONArray("extras") ?: return emptyList()
        val out = ArrayList<Extra>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val url = e.optString("url").trim()
            val nome = e.optString("nome").trim().ifEmpty { url.substringAfterLast('/').substringBefore('?') }
            val pasta = e.optString("pasta").trim().trim('/')
            val destino = e.optString("destino", "download").trim().lowercase().ifEmpty { "download" }
            if (url.isNotEmpty() && nome.isNotEmpty() && !nome.contains("/") && !pasta.contains("..")) out.add(Extra(url, pasta, nome, destino))
        }
        return out
    }

    /** Baixa o extra para o destino. onProgress(pct ou -1, bytes). true = ok (ou já estava lá, mesmo tamanho). */
    fun fetch(ctx: Context, ex: Extra, onProgress: (Int, Long) -> Unit): Boolean {
        if (ex.destino != "download") return false   // OBB/data: ainda não suportado
        return toDownloads(ctx, ex, onProgress)
    }

    /** Apaga o extra (só cópias feitas por este app). */
    fun remove(ctx: Context, ex: Extra) {
        if (ex.destino != "download") return
        if (Build.VERSION.SDK_INT >= 29) {
            val rel = Environment.DIRECTORY_DOWNLOADS + "/" + (if (ex.pasta.isNotEmpty()) ex.pasta + "/" else "")
            try {
                ctx.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(rel, ex.nome))
            } catch (_: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = if (ex.pasta.isNotEmpty()) File(base, ex.pasta) else base
            File(dir, ex.nome).delete()
            if (ex.pasta.isNotEmpty()) dir.delete()   // só apaga a pasta se ficou vazia
        }
    }

    // ---------- Download/<pasta>/<nome> ----------
    private fun toDownloads(ctx: Context, ex: Extra, onProgress: (Int, Long) -> Unit): Boolean {
        val remoteSize = headSize(ex.url)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val rel = Environment.DIRECTORY_DOWNLOADS + "/" + (if (ex.pasta.isNotEmpty()) ex.pasta + "/" else "")
            // Cópia anterior feita por nós: se tem o mesmo tamanho, pula; senão apaga e baixa de novo.
            try {
                resolver.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    arrayOf(rel, ex.nome), null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0); val size = c.getLong(1)
                        val uri = Uri.withAppendedPath(coll, id.toString())
                        if (remoteSize > 0 && size == remoteSize) return true
                        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ex.nome)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(coll, values) ?: return false
            val ok = try { resolver.openOutputStream(uri, "w")?.use { out -> download(ex.url, out, onProgress) } ?: false } catch (_: Exception) { false }
            if (!ok) { try { resolver.delete(uri, null, null) } catch (_: Exception) {}; return false }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            try { resolver.update(uri, values, null, null) } catch (_: Exception) {}
            return true
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = if (ex.pasta.isNotEmpty()) File(base, ex.pasta) else base
            dir.mkdirs()
            val dest = File(dir, ex.nome)
            if (remoteSize > 0 && dest.exists() && dest.length() == remoteSize) return true
            val tmp = File(dir, ex.nome + ".part")
            val ok = try { tmp.outputStream().use { download(ex.url, it, onProgress) } } catch (_: Exception) { false }
            if (!ok) { tmp.delete(); return false }
            dest.delete()
            return tmp.renameTo(dest)
        }
    }

    // ---------- HTTP ----------
    private fun open(urlStr: String, method: String): HttpURLConnection? {
        var url = urlStr; var redirects = 0
        while (redirects < 6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method; instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 300000
            }
            val code = c.responseCode
            if (code in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); if (loc.isNullOrBlank()) return null; url = loc; redirects++; continue }
            if (code != 200) { c.disconnect(); return null }
            return c
        }
        return null
    }

    private fun headSize(url: String): Long = try {
        val c = open(url, "HEAD"); val n = c?.contentLengthLong ?: -1L; c?.disconnect(); n
    } catch (_: Exception) { -1L }

    private fun download(url: String, out: OutputStream, onProgress: (Int, Long) -> Unit): Boolean {
        val c = open(url, "GET") ?: return false
        try {
            val total = c.contentLengthLong
            c.inputStream.use { inp ->
                val buf = ByteArray(64 * 1024); var readTotal = 0L; var lastPct = -1; var lastMark = 0L
                while (true) {
                    val r = inp.read(buf); if (r < 0) break
                    out.write(buf, 0, r); readTotal += r
                    if (total > 0) { val pct = ((readTotal * 100) / total).toInt(); if (pct != lastPct) { lastPct = pct; onProgress(pct, readTotal) } }
                    else if (readTotal - lastMark >= 1_000_000) { lastMark = readTotal; onProgress(-1, readTotal) }
                }
                out.flush()
                return total <= 0 || readTotal == total
            }
        } catch (_: Exception) { return false } finally { c.disconnect() }
    }
}
