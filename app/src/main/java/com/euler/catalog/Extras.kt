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
 *   "raiz" -> .zip descompactado na RAIZ do armazenamento mantendo as pastas do zip
 *             (ex.: zip com a pasta nfsu2 → /sdcard/nfsu2). Android 10: direto. Android 11+: precisa do
 *             acesso a todos os arquivos (liberado sozinho pelo ADB, se a depuração estiver ativa);
 *             sem isso, vai para Download/<pasta> e o carro mostra como mover.
 *   "download_zip" -> .zip descompactado em Download/<pasta>/ (se o zip tiver uma única pasta por fora,
 *                     ela é "aberta": o conteúdo dela vai direto para Download/<pasta>/)
 * Regra por Android:
 *   Android 10 (ou anterior): obb e data vão direto para a pasta certa.
 *   Android 11+: obb tenta direto (instaladores costumam ter acesso); data nunca dá direto.
 *   Quando não dá direto, vai para Download/<pasta>/<pacote>/ e o app mostra como mover manualmente.
 */
object Extras {

    data class Extra(val url: String, val pasta: String, val nome: String, val destino: String = "download")

    /** ok = deu certo; manual = instrução quando o arquivo não pôde ir direto para a pasta certa; erro = motivo da falha. */
    data class Result(val ok: Boolean, val manual: String? = null, val erro: String? = null)

    /** Falha com motivo legível (sem espaço, arquivo não encontrado, conexão caiu...). */
    class DlError(msg: String) : Exception(msg)
    private var lastError: String? = null

    private fun gb(n: Long) = String.format(java.util.Locale.US, "%.1f GB", n / 1073741824.0)

    /** Confere se há espaço livre suficiente na pasta (com folga de 200 MB). */
    private fun checkSpace(dir: File, needed: Long) {
        if (needed <= 0) return
        val free = try { android.os.StatFs(dir.absolutePath).availableBytes } catch (e: Exception) { return }
        if (free < needed + 200L * 1024 * 1024) throw DlError("sem espaço no carro: precisa de ~${gb(needed)} livres, tem ${gb(free)}")
    }

    fun parse(o: JSONObject): List<Extra> {
        val arr = o.optJSONArray("extras") ?: return emptyList()
        val out = ArrayList<Extra>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val url = e.optString("url").trim()
            val nome = e.optString("nome").trim().ifEmpty { Uri.decode(url.substringAfterLast('/').substringBefore('?')) }
            val pasta = e.optString("pasta").trim().trim('/')
            val destino = e.optString("destino", "download").trim().lowercase().ifEmpty { "download" }
            val pastaOk = if (destino == "download_zip" && pasta.isEmpty()) nome.substringBeforeLast('.').trim() else pasta
            if (url.isNotEmpty() && nome.isNotEmpty() && !nome.contains("/") && !pastaOk.contains("..")) out.add(Extra(url, pastaOk, nome, destino))
        }
        return out
    }

    /** Precisa da permissão de armazenamento (só Android 10 ou anterior, para gravar em Android/obb e Android/data). */
    fun needsStoragePermission(extras: List<Extra>): Boolean =
        Build.VERSION.SDK_INT <= 29 && extras.any { it.destino == "obb" || it.destino == "data" || it.destino == "raiz" }

    private fun androidName(): String = "Android " + (Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())

    /** Baixa o extra para o destino. report recebe o texto de progresso ("Baixando X… 40%"). cancel() = cancelar agora. */
    fun fetch(ctx: Context, ex: Extra, pkg: String, cancel: () -> Boolean = { false }, report: (String) -> Unit): Result {
        lastError = null
        return try {
            val r = when (ex.destino) {
                "obb" -> special(ctx, ex, pkg, "obb", cancel, report)
                "data" -> special(ctx, ex, pkg, "data", cancel, report)
                "raiz" -> rootExtract(ctx, ex, pkg, cancel, report)
                "download_zip" -> Result(zipTo(ctx, ex, pkg, null, cancel, report, layout = ::downloadZipLayout) { rel, inp ->
                    val dir = rel.substringBeforeLast('/', "")
                    writeToDownloads(ctx, downloadsRel(ex.pasta) + (if (dir.isNotEmpty()) "$dir/" else ""), rel.substringAfterLast('/'), inp)
                })
                else -> Result(toDownloads(ctx, ex.url, downloadsRel(ex.pasta), ex.nome, cancel) { p, b -> report(progressText("Baixando", ex.nome, p, b)) })
            }
            if (!r.ok && r.erro == null) r.copy(erro = lastError) else r
        } catch (e: DlError) { Result(false, erro = e.message) }
        catch (e: Exception) { Result(false, erro = lastError ?: e.message) }
    }

    // ---------- raiz do armazenamento ----------
    // Pastas que NUNCA apagamos/sobrescrevemos na raiz, mesmo que um zip as traga.
    private val PROTECTED = setOf("android", "dcim", "download", "downloads", "pictures", "music", "movies", "documents",
        "alarms", "notifications", "podcasts", "ringtones", "audiobooks", "recordings", "screenshots")

    @Suppress("DEPRECATION")
    private fun storageRoot(): File = Environment.getExternalStorageDirectory()

    /** Tem permissão para escrever direto na raiz? (Android 11+: "acesso a todos os arquivos"; tenta liberar pelo ADB.) */
    private fun canWriteRoot(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) return true
            try { AdbShell.run(ctx, "appops set --uid ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow; appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow", 15000) } catch (_: Exception) {}
            return Environment.isExternalStorageManager()
        }
        return storageRoot().canWrite()
    }

    private fun rootPrefs(ctx: Context) = ctx.getSharedPreferences("extras_raiz", Context.MODE_PRIVATE)

    private fun rootExtract(ctx: Context, ex: Extra, pkg: String, cancel: () -> Boolean, report: (String) -> Unit): Result {
        if (!ex.nome.endsWith(".zip", true)) { lastError = "para a raiz, envie a pasta compactada em .zip"; return Result(false) }
        val root = storageRoot()
        val tops = HashSet<String>()
        val layout = { names: List<String>, _: String ->
            Pair({ n: String ->
                val x = n.replace('\\', '/').trimStart('/')
                val top = x.substringBefore('/')
                if (x.isEmpty() || x.startsWith("__MACOSX/") || x.endsWith(".DS_Store") || top.lowercase() in PROTECTED) null
                else { tops += top; x }
            }, null as String?)
        }
        if (canWriteRoot(ctx)) {
            val ok = zipTo(ctx, ex, pkg, root, cancel, report, layout = layout) { rel, inp -> writeFile(File(root, rel), inp) }
            if (ok) {
                // Guarda quais pastas criamos na raiz, pra apagar só elas ao desinstalar.
                rootPrefs(ctx).edit().putStringSet(ex.url, tops.filter { it.contains('/') == false && it.isNotBlank() }.toSet()).apply()
                return Result(true)
            }
            if (lastError != null) return Result(false)
        }
        // Sem acesso à raiz: extrai em Download/<pasta>/ e explica como mover.
        val base = downloadsRel(ex.pasta.ifEmpty { pkg })
        val ok = zipTo(ctx, ex, pkg, null, cancel, report, layout = layout) { r, inp ->
            val sub = r.substringBeforeLast('/', ""); val name = r.substringAfterLast('/')
            writeToDownloads(ctx, base + (if (sub.isNotEmpty()) "$sub/" else ""), name, inp)
        }
        if (!ok) return Result(false)
        val folder = tops.firstOrNull() ?: ex.pasta
        val manual = "Este carro (${androidName()}) não permitiu gravar direto na memória principal. Os arquivos foram colocados na pasta Download.\n\n" +
            "Faça manualmente:\n" +
            "1. Abra o app Arquivos do carro.\n" +
            "2. Entre em Download → ${ex.pasta.ifEmpty { pkg }}.\n" +
            "3. Segure a pasta \"$folder\" e escolha Mover.\n" +
            "4. Cole na memória principal (a primeira tela do app Arquivos, fora de qualquer pasta).\n" +
            "5. Depois abra o jogo."
        return Result(true, manual)
    }

    private fun removeRootFolders(ctx: Context, ex: Extra) {
        val tops = rootPrefs(ctx).getStringSet(ex.url, null) ?: return
        val root = storageRoot()
        for (t in tops) {
            if (t.isBlank() || t.contains('/') || t.contains("..") || t.lowercase() in PROTECTED) continue
            try { File(root, t).deleteRecursively() } catch (_: Exception) {}
        }
        rootPrefs(ctx).edit().remove(ex.url).apply()
        removeExtractedFolder(ctx, ex.pasta)   // cópia do plano B em Download, se houver
    }

    // ---------- OBB / data ----------
    private fun special(ctx: Context, ex: Extra, pkg: String, kind: String, cancel: () -> Boolean, report: (String) -> Unit): Result {
        val isZip = ex.nome.endsWith(".zip", true)
        @Suppress("DEPRECATION")
        val root = File(Environment.getExternalStorageDirectory(), "Android/$kind/$pkg")
        // data: só Android 10 ou anterior. obb: tenta em qualquer versão.
        val tryDirect = kind == "obb" || Build.VERSION.SDK_INT <= 29
        if (tryDirect) {
            val ok = try {
                if (!root.exists()) root.mkdirs()
                if (!root.isDirectory || !root.canWrite()) false
                else if (isZip) zipTo(ctx, ex, pkg, root, cancel, report) { rel, inp -> writeFile(File(root, rel), inp) }
                else {
                    val dest = File(root, ex.nome)
                    val remote = remoteSize(ex.url)
                    if (remote > 0 && dest.exists() && dest.length() == remote) true
                    else {
                        checkSpace(root, remote - (if (dest.exists()) dest.length() else 0L))
                        val tmp = File(root, ex.nome + ".part")
                        val okd = downloadFile(ex.url, tmp, cancel) { p, b -> report(progressText("Baixando", ex.nome, p, b)) }
                        if (okd) { dest.delete(); tmp.renameTo(dest) } else false
                    }
                }
            } catch (e: DlError) { throw e } catch (e: Exception) { false }
            if (ok) return Result(true)
        }
        // Sem acesso direto: salva em Download/<pasta>/<pacote>/ para o usuário mover.
        val baseRel = downloadsRel(ex.pasta.ifEmpty { pkg }) + pkg + "/"
        val ok = if (isZip) zipTo(ctx, ex, pkg, null, cancel, report) { r, inp ->
                val sub = r.substringBeforeLast('/', ""); val name = r.substringAfterLast('/')
                writeToDownloads(ctx, baseRel + (if (sub.isNotEmpty()) "$sub/" else ""), name, inp)
            }
            else toDownloads(ctx, ex.url, baseRel, ex.nome, cancel) { p, b -> report(progressText("Baixando", ex.nome, p, b)) }
        if (!ok) return Result(false)
        val pastaTxt = "Download → " + (ex.pasta.ifEmpty { pkg })
        val manual = "Este carro (${androidName()}) não permite a instalação automática da pasta $kind. Os arquivos foram colocados na pasta Download.\n\n" +
            "Faça manualmente:\n" +
            "1. Abra o app Arquivos do carro.\n" +
            "2. Entre em $pastaTxt.\n" +
            "3. Segure a pasta \"$pkg\" e escolha Mover.\n" +
            "4. Cole dentro de Android → $kind.\n" +
            "5. Depois abra o jogo."
        return Result(true, manual)
    }

    /**
     * Decide o que do zip vai para a pasta do jogo:
     *  - se existe uma pasta com o nome do pacote em qualquer nível (ex.: "OBB DFL/com.jogo/..."), usa só o conteúdo dela;
     *  - senão, se tudo está dentro de uma única pasta (ex.: "OBB DFL/main.obb"), tira essa pasta de fora;
     *  - senão, usa como está.
     * Devolve (caminho no zip -> caminho relativo dentro de Android/<tipo>/<pacote>/, ou null para ignorar) e a pasta "embrulho".
     */
    private fun zipLayout(names: List<String>, pkg: String): Pair<(String) -> String?, String?> {
        fun norm(n: String) = n.replace('\\', '/').trimStart('/')
        val clean = names.map { norm(it) }.filter { !it.startsWith("__MACOSX/") && !it.endsWith("/.DS_Store") && it != ".DS_Store" }
        fun pkgCut(n: String): Int { if (n.startsWith("$pkg/")) return pkg.length + 1; val i = n.indexOf("/$pkg/"); return if (i >= 0) i + pkg.length + 2 else -1 }
        if (clean.any { pkgCut(it) >= 0 }) {
            val first = clean.first { pkgCut(it) >= 0 }
            val wrapper = first.substring(0, pkgCut(first) - pkg.length - 1).trimEnd('/').substringBefore('/').ifEmpty { null }
            return Pair({ n: String -> val x = norm(n); val c = pkgCut(x); if (c >= 0 && c < x.length) x.substring(c) else null }, wrapper)
        }
        val tops = clean.map { it.substringBefore('/', "") }.toSet()
        if (tops.size == 1 && tops.first().isNotEmpty() && clean.all { it.contains('/') }) {
            val top = tops.first()
            return Pair({ n: String -> val x = norm(n); if (x.startsWith("$top/") && x.length > top.length + 1) x.substring(top.length + 1) else null }, top)
        }
        return Pair({ n: String -> val x = norm(n); if (x.startsWith("__MACOSX/") || x.isEmpty()) null else x }, null)
    }

    /** Baixa o zip para o cache, descompacta entrada por entrada com write(caminho relativo, conteúdo) e apaga o zip. */
    /** Download/<pasta>: mantém a estrutura do zip; se tudo estiver dentro de UMA pasta, abre essa pasta. */
    private fun downloadZipLayout(names: List<String>, @Suppress("UNUSED_PARAMETER") pkg: String): Pair<(String) -> String?, String?> {
        fun norm(n: String) = n.replace('\\', '/').trimStart('/')
        val clean = names.map { norm(it) }.filter { !it.startsWith("__MACOSX/") && !it.endsWith(".DS_Store") }
        val tops = clean.map { it.substringBefore('/') }.toSet()
        val wrap = if (tops.size == 1 && clean.all { it.contains('/') }) tops.first() + "/" else ""
        return Pair({ n: String -> val x = norm(n); if (x.startsWith("__MACOSX/") || x.endsWith(".DS_Store") || x.isEmpty()) null else x.removePrefix(wrap).ifEmpty { null } }, null)
    }

    private fun zipTo(ctx: Context, ex: Extra, pkg: String, root: File?, cancel: () -> Boolean, report: (String) -> Unit,
                      layout: (List<String>, String) -> Pair<(String) -> String?, String?> = ::zipLayout, write: (String, InputStream) -> Boolean): Boolean {
        val tmp = File(ctx.cacheDir, "x_" + ex.nome.hashCode() + ".zip")
        var keepPart = false
        try {
            // zip baixado + conteúdo extraído: precisa de ~2x o tamanho (descontando o que já foi baixado antes)
            val remote = remoteSize(ex.url)
            if (remote > 0) checkSpace(ctx.cacheDir, remote * 2 - (if (tmp.exists()) tmp.length() else 0L))
            val okd = downloadFile(ex.url, tmp, cancel) { p, b -> report(progressText("Baixando", ex.nome, p, b)) }
            if (!okd) { keepPart = true; return false }   // mantém o pedaço baixado para retomar na próxima tentativa
            ZipFile(tmp).use { zf ->
                val entries = zf.entries().toList().filter { !it.isDirectory && !it.name.contains("..") }
                val (map, wrapper) = layout(entries.map { it.name }, pkg)
                // Limpa a pasta "embrulho" que uma instalação antiga possa ter deixado dentro da pasta do jogo (ex.: Android/obb/com.jogo/OBB DFL).
                if (root != null && wrapper != null && wrapper != pkg) try { File(root, wrapper).deleteRecursively() } catch (_: Exception) {}
                val total = entries.sumOf { maxOf(it.size, 0L) }.coerceAtLeast(1L); var done = 0L; var lastPct = -1
                fun tick() { val pct = ((done * 100) / total).toInt().coerceIn(0, 100); if (pct != lastPct) { lastPct = pct; report("Extraindo ${ex.nome}…  $pct%") } }
                tick()
                for (en in entries) {
                    if (cancel()) return false
                    val rel = map(en.name)
                    if (rel == null || rel.isEmpty()) { done += maxOf(en.size, 0L); tick(); continue }
                    // conta os bytes enquanto extrai, para a porcentagem andar mesmo em arquivos enormes (ex.: um .obb de 1 GB)
                    val ok = zf.getInputStream(en).use { raw ->
                        val counting = object : java.io.FilterInputStream(raw) {
                            override fun read(): Int { val r = super.read(); if (r >= 0) { done++; if (done % 1048576L == 0L) tick() }; return r }
                            override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) { done += r; tick() }; return r }
                        }
                        write(rel, counting)
                    }
                    if (!ok) return false
                }
                done = total; tick()
            }
            return true
        } catch (e: DlError) { throw e }
        catch (e: java.util.zip.ZipException) { lastError = "o .zip está corrompido ou incompleto"; return false }
        catch (e: java.io.IOException) { lastError = if ((e.message ?: "").contains("ENOSPC") || (e.message ?: "").contains("No space")) "sem espaço no carro" else "erro ao extrair: ${e.message}"; return false }
        catch (e: Exception) { lastError = "erro ao extrair: ${e.message}"; return false }
        finally { if (!keepPart) try { tmp.delete() } catch (_: Exception) {} }
    }

    private fun writeFile(dest: File, inp: InputStream): Boolean = try {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { inp.copyTo(it, 64 * 1024) }; true
    } catch (e: Exception) { false }

    /** Apaga o extra (só cópias feitas por este app na pasta Download). OBB/data o Android apaga junto com o app. */
    fun remove(ctx: Context, ex: Extra) {
        if (ex.destino == "download_zip") { removeExtractedFolder(ctx, ex.pasta); return }
        if (ex.destino == "raiz") { removeRootFolders(ctx, ex); return }
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

    /** Apaga o que extraímos em Download/<pasta>/ (só arquivos criados por este app; nunca a pasta Download inteira). */
    private fun removeExtractedFolder(ctx: Context, pasta: String) {
        if (pasta.isBlank()) return
        val rel = downloadsRel(pasta)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ctx.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", arrayOf("$rel%"))
            } catch (_: Exception) {}
        } else {
            try { legacyDir(rel).deleteRecursively() } catch (_: Exception) {}
        }
    }

    // ---------- pasta Download ----------
    private fun downloadsRel(pasta: String) = Environment.DIRECTORY_DOWNLOADS + "/" + (if (pasta.isNotEmpty()) "$pasta/" else "")

    /** Baixa url para Download/<rel>/<nome>. Pula se já existe cópia nossa com o mesmo tamanho. */
    private fun toDownloads(ctx: Context, url: String, rel: String, nome: String, cancel: () -> Boolean, onProgress: (Int, Long) -> Unit): Boolean {
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
            @Suppress("DEPRECATION")
            checkSpace(Environment.getExternalStorageDirectory(), remote)
            for (tentativa in 1..3) {
                val uri = insertPending(ctx, rel, nome) ?: run { lastError = "não consegui criar o arquivo na pasta Download"; return false }
                val ok = try { resolver.openOutputStream(uri, "w")?.use { download(url, it, cancel, onProgress) } ?: false } catch (_: Exception) { false }
                if (finishPending(ctx, uri, ok)) return true
                if (lastError?.contains("404") == true) return false
                try { Thread.sleep(3000L * tentativa) } catch (_: Exception) {}
            }
            return false
        } else {
            val dir = legacyDir(rel); dir.mkdirs()
            val dest = File(dir, nome)
            if (remote > 0 && dest.exists() && dest.length() == remote) return true
            checkSpace(dir, remote)
            val tmp = File(dir, "$nome.part")
            if (!downloadFile(url, tmp, cancel, onProgress)) return false
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
        if (pct >= 0) "$verb $nome…  $pct% · ${sizeText(bytes)}" else "$verb $nome…  ${sizeText(bytes)}"

    /** "850 MB" / "1,3 GB" */
    fun sizeText(n: Long): String = if (n >= 1_000_000_000L) String.format(java.util.Locale("pt", "BR"), "%.1f GB", n / 1_073_741_824.0)
        else "${maxOf(1L, n / 1_048_576L)} MB"

    // ---------- HTTP ----------
    private fun open(urlStr: String, method: String, from: Long = 0L): HttpURLConnection? {
        var url = urlStr; var redirects = 0
        try {
            while (redirects < 6) {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method; instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 120000
                    if (from > 0) setRequestProperty("Range", "bytes=$from-")
                }
                val code = c.responseCode
                if (code in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); if (loc.isNullOrBlank()) return null; url = loc; redirects++; continue }
                if (code == 200 || (from > 0 && code == 206)) return c
                lastError = if (code == 404) "arquivo não encontrado no servidor (404)" else "o servidor respondeu erro $code"
                c.disconnect(); return null
            }
        } catch (e: Exception) { lastError = "sem conexão com o servidor" }
        return null
    }

    /** Baixa para um arquivo, retomando de onde parou se a conexão cair (até 6 tentativas). */
    private fun downloadFile(url: String, dest: File, cancel: () -> Boolean = { false }, onProgress: (Int, Long) -> Unit): Boolean {
        val total = remoteSize(url)
        if (total > 0 && dest.exists() && dest.length() > total) dest.delete()
        if (total > 0 && dest.exists() && dest.length() == total) return true
        for (tentativa in 1..6) {
            if (cancel()) return false
            val have = if (dest.exists()) dest.length() else 0L
            val c = open(url, "GET", have)
            if (c == null) {
                if (lastError?.contains("404") == true) return false
                try { Thread.sleep(4000L * tentativa) } catch (_: Exception) {}
                continue
            }
            try {
                val resumed = c.responseCode == 206
                val start = if (resumed) have else 0L
                val full = if (total > 0) total else (if (c.contentLengthLong > 0) start + c.contentLengthLong else -1L)
                java.io.FileOutputStream(dest, resumed).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(64 * 1024); var readTotal = start; var lastPct = -1; var lastMark = 0L
                        while (true) {
                            if (cancel()) return false
                            val r = inp.read(buf); if (r < 0) break
                            out.write(buf, 0, r); readTotal += r
                            if (full > 0) { val pct = ((readTotal * 100) / full).toInt(); if (pct != lastPct) { lastPct = pct; onProgress(pct, readTotal) } }
                            else if (readTotal - lastMark >= 1_000_000) { lastMark = readTotal; onProgress(-1, readTotal) }
                        }
                    }
                }
                if (full <= 0 || dest.length() == full) return true
                lastError = "a conexão caiu no meio do download"
            } catch (e: java.io.IOException) {
                val m = e.message ?: ""
                if (m.contains("ENOSPC") || m.contains("No space")) { lastError = "sem espaço no carro"; return false }
                lastError = "a conexão caiu no meio do download"
            } finally { c.disconnect() }
            try { Thread.sleep(4000L * tentativa) } catch (_: Exception) {}
        }
        return false
    }

    private fun remoteSize(url: String): Long = try {
        val c = open(url, "HEAD"); val n = c?.contentLengthLong ?: -1L; c?.disconnect(); n
    } catch (_: Exception) { -1L }

    private fun download(url: String, out: OutputStream, cancel: () -> Boolean = { false }, onProgress: (Int, Long) -> Unit): Boolean {
        val c = open(url, "GET") ?: return false
        try {
            val total = c.contentLengthLong
            c.inputStream.use { inp ->
                val buf = ByteArray(64 * 1024); var readTotal = 0L; var lastPct = -1; var lastMark = 0L
                while (true) {
                    if (cancel()) return false
                    val r = inp.read(buf); if (r < 0) break
                    out.write(buf, 0, r); readTotal += r
                    if (total > 0) { val pct = ((readTotal * 100) / total).toInt(); if (pct != lastPct) { lastPct = pct; onProgress(pct, readTotal) } }
                    else if (readTotal - lastMark >= 1_000_000) { lastMark = readTotal; onProgress(-1, readTotal) }
                }
                out.flush()
                if (total > 0 && readTotal != total) { lastError = "a conexão caiu no meio do download"; return false }
                return true
            }
        } catch (e: Exception) {
            val m = e.message ?: ""
            lastError = if (m.contains("ENOSPC") || m.contains("No space")) "sem espaço no carro" else "a conexão caiu no meio do download"
            return false
        } finally { c.disconnect() }
    }
}
