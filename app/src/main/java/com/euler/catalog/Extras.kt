package com.euler.catalog

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Arquivos extras de um app do catálogo, baixados antes do APK.
 * No catalog.json:
 *   "extras": [ { "url": "...", "nome": "Disco 1.rvz", "pasta": "Resident Evil 4", "destino": "download" } ]
 * destino:
 *   "download" (padrão) -> Download/<pasta>/<nome>
 *   "obb"  -> Android/obb/<pacote>/   (arquivo .obb, ou .zip que é descompactado lá)
 *   "data" -> Android/data/<pacote>/  (.zip com a pasta do jogo, descompactado lá)
 * Regra por Android:
 *   Android 10 (ou anterior): obb e data vão direto para a pasta certa.
 *   Android 11+: obb tenta direto (instaladores costumam ter acesso); data nunca dá direto.
 *   Quando não dá direto, vai para Download/<pasta>/<pacote>/ e o app mostra como mover manualmente.
 */
object Extras {

    data class Extra(val url: String, val pasta: String, val nome: String, val destino: String = "download")

    /** ok = deu certo; manual = instrução para o usuário quando o arquivo não pôde ir direto para a pasta certa. */
    data class Result(val ok: Boolean, val manual: String? = null)

    fun parse(o: JSONObject): List<Extra> {
        val arr = o.optJSONArray("extras") ?: return emptyList()
        val out = ArrayList<Extra>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val url = e.optString("url").trim()
            val nome = e.optString("nome").trim().ifEmpty { Uri.decode(url.substringAfterLast('/').substringBefore('?')) }
            val pasta = e.optString("pasta").trim().trim('/')
            val destino = e.optString("destino", "download").trim().lowercase().ifEmpty { "download" }
            if (url.isNotEmpty() && nome.isNotEmpty() && !nome.contains("/") && !pasta.contains("..")) out.add(Extra(url, pasta, nome, destino))
        }
        return out
    }

    /** Precisa da permissão de armazenamento (só Android 10 ou anterior, para gravar em Android/obb e Android/data). */
    fun needsStoragePermission(extras: List<Extra>): Boolean =
        Build.VERSION.SDK_INT <= 29 && extras.any { it.destino == "obb" || it.destino == "data" }

    private fun androidName(): String = "Android " + (Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())

    /** Baixa o extra para o destino. report recebe o texto de progresso ("Baixando X… 40%"). */
    fun fetch(ctx: Context, ex: Extra, pkg: String, report: (String) -> Unit): Result {
        return when (ex.destino) {
            "obb" -> special(ctx, ex, pkg, "obb", report)
            "data" -> special(ctx, ex, pkg, "data", report)
            else -> Result(toDownloads(ctx, ex.url, downloadsRel(ex.pasta), ex.nome) { p, b -> report(progressText("Baixando", ex.nome, p, b)) })
        }
    }

    // ---------- OBB / data ----------
    private fun special(ctx: Context, ex: Extra, pkg: String, kind: String, report: (String) -> Unit): Result {
        val isZip = ex.nome.endsWith(".zip", true)
        @Suppress("DEPRECATION")
        val root = File(Environment.getExternalStorageDirectory(), "Android/$kind/$pkg")
        // data: só Android 10 ou anterior. obb: tenta em qualquer versão.
        val tryDirect = kind == "obb" || Build.VERSION.SDK_INT <= 29
        if (tryDirect) {
            val ok = try {
                if (!root.exists()) root.mkdirs()
                if (!root.isDirectory || !root.canWrite()) false
                else if (isZip) zipTo(ctx, ex, report) { rel, inp -> writeFile(File(root, stripRoot(rel, pkg, kind)), inp) }
                else {
                    val dest = File(root, ex.nome)
                    val remote = remoteSize(ex.url)
                    if (remote > 0 && dest.exists() && dest.length() == remote) true
                    else {
                        val tmp = File(root, ex.nome + ".part")
                        val okd = tmp.outputStream().use { download(ex.url, it) { p, b -> report(progressText("Baixando", ex.nome, p, b)) } }
                        if (okd) { dest.delete(); tmp.renameTo(dest) } else { tmp.delete(); false }
                    }
                }
            } catch (e: Exception) { false }
            if (ok) return Result(true)
        }
        // Sem acesso direto: salva em Download/<pasta>/<pacote>/ para o usuário mover.
        val baseRel = downloadsRel(ex.pasta.ifEmpty { pkg }) + pkg + "/"
        val ok = if (isZip) zipTo(ctx, ex, report) { rel, inp ->
                val r = stripRoot(rel, pkg, kind)
                val sub = r.substringBeforeLast('/', ""); val name = r.substringAfterLast('/')
                writeToDownloads(ctx, baseRel + (if (sub.isNotEmpty()) "$sub/" else ""), name, inp)
            }
            else toDownloads(ctx, ex.url, baseRel, ex.nome) { p, b -> report(progressText("Baixando", ex.nome, p, b)) }
        if (!ok) return Result(false)
        val pastaTxt = "Download → " + (ex.pasta.ifEmpty { pkg })
        val manual = "Este carro é ${androidName()}: o Android não deixa colocar a pasta $kind automaticamente.\n\n" +
            "Os arquivos foram salvos em $pastaTxt → $pkg.\n\n" +
            "Para terminar: abra o app Arquivos do carro, entre em $pastaTxt, segure a pasta \"$pkg\", escolha Mover e cole dentro de Android → $kind. Depois abra o jogo."
        return Result(true, manual)
    }

    /** Remove do caminho do zip a pasta raiz repetida (ex.: "com.jogo/..." ou "Android/data/com.jogo/..."). */
    private fun stripRoot(rel: String, pkg: String, kind: String): String {
        var r = rel.replace('\\', '/').trimStart('/')
        val prefixes = listOf("Android/$kind/$pkg/", "$kind/$pkg/", "$pkg/")
        for (p in prefixes) if (r.startsWith(p)) { r = r.substring(p.length); break }
        return r
    }

    /** Baixa o zip para o cache, descompacta entrada por entrada com write(caminho, conteúdo) e apaga o zip. */
    private fun zipTo(ctx: Context, ex: Extra, report: (String) -> Unit, write: (String, InputStream) -> Boolean): Boolean {
        val tmp = File(ctx.cacheDir, "x_" + ex.nome.hashCode() + ".zip")
        try {
            val okd = tmp.outputStream().use { download(ex.url, it) { p, b -> report(progressText("Baixando", ex.nome, p, b)) } }
            if (!okd) return false
            ZipFile(tmp).use { zf ->
                val entries = zf.entries().toList().filter { !it.isDirectory && !it.name.contains("..") }
                val total = entries.sumOf { maxOf(it.size, 0L) }.coerceAtLeast(1L); var done = 0L; var lastPct = -1
                for (en in entries) {
                    val ok = zf.getInputStream(en).use { write(en.name, it) }
                    if (!ok) return false
                    done += maxOf(en.size, 0L)
                    val pct = ((done * 100) / total).toInt()
                    if (pct != lastPct) { lastPct = pct; report("Extraindo ${ex.nome}…  $pct%") }
                }
            }
            return true
        } catch (e: Exception) { return false } finally { try { tmp.delete() } catch (_: Exception) {} }
    }

    private fun writeFile(dest: File, inp: InputStream): Boolean = try {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { inp.copyTo(it, 64 * 1024) }; true
    } catch (e: Exception) { false }

    /** Apaga o extra (só cópias feitas por este app na pasta Download). OBB/data o Android apaga junto com o app. */
    fun remove(ctx: Context, ex: Extra) {
        if (ex.destino != "download") return
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ctx.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(downloadsRel(ex.pasta), ex.nome))
            } catch (_: Exception) {}
        } else {
            val dir = legacyDir(downloadsRel(ex.pasta))
            File(dir, ex.nome).delete()
            if (ex.pasta.isNotEmpty()) dir.delete()   // só apaga a pasta se ficou vazia
        }
    }

    // ---------- pasta Download ----------
    private fun downloadsRel(pasta: String) = Environment.DIRECTORY_DOWNLOADS + "/" + (if (pasta.isNotEmpty()) "$pasta/" else "")

    /** Baixa url para Download/<rel>/<nome>. Pula se já existe cópia nossa com o mesmo tamanho. */
    private fun toDownloads(ctx: Context, url: String, rel: String, nome: String, onProgress: (Int, Long) -> Unit): Boolean {
        val remote = remoteSize(url)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            try {
                resolver.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    arrayOf(rel, nome), null)?.use { c ->
                    while (c.moveToNext()) {
                        val uri = Uri.withAppendedPath(coll, c.getLong(0).toString())
                        if (remote > 0 && c.getLong(1) == remote) return true
                        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            val uri = insertPending(ctx, rel, nome) ?: return false
            val ok = try { resolver.openOutputStream(uri, "w")?.use { download(url, it, onProgress) } ?: false } catch (_: Exception) { false }
            return finishPending(ctx, uri, ok)
        } else {
            val dir = legacyDir(rel); dir.mkdirs()
            val dest = File(dir, nome)
            if (remote > 0 && dest.exists() && dest.length() == remote) return true
            val tmp = File(dir, "$nome.part")
            val ok = try { tmp.outputStream().use { download(url, it, onProgress) } } catch (_: Exception) { false }
            if (!ok) { tmp.delete(); return false }
            dest.delete(); return tmp.renameTo(dest)
        }
    }

    /** Grava um arquivo (vindo de um zip) em Download/<rel>/<nome>, substituindo cópia nossa anterior. */
    private fun writeToDownloads(ctx: Context, rel: String, nome: String, inp: InputStream): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ctx.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(rel, nome))
            } catch (_: Exception) {}
            val uri = insertPending(ctx, rel, nome) ?: return false
            val ok = try { ctx.contentResolver.openOutputStream(uri, "w")?.use { inp.copyTo(it, 64 * 1024); true } ?: false } catch (_: Exception) { false }
            return finishPending(ctx, uri, ok)
        }
        return writeFile(File(legacyDir(rel), nome), inp)
    }

    private fun insertPending(ctx: Context, rel: String, nome: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try { ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) } catch (_: Exception) { null }
    }

    private fun finishPending(ctx: Context, uri: Uri, ok: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 29) return ok
        if (!ok) { try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}; return false }
        val v = ContentValues(); v.put(MediaStore.MediaColumns.IS_PENDING, 0)
        try { ctx.contentResolver.update(uri, v, null, null) } catch (_: Exception) {}
        return true
    }

    @Suppress("DEPRECATION")
    private fun legacyDir(rel: String) = File(Environment.getExternalStorageDirectory(), rel.trimEnd('/'))

    private fun progressText(verb: String, nome: String, pct: Int, bytes: Long) =
        if (pct >= 0) "$verb $nome…  $pct%" else "$verb $nome…  ${bytes / 1_000_000} MB"

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

    private fun remoteSize(url: String): Long = try {
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
