package com.euler.catalog

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Resultado do registro/heartbeat no servidor. */
data class RegResult(val status: String, val wipe: Boolean, val plan: String, val grupo: String)

/** Lógica compartilhada: ativação no servidor + catálogo + remoção de apps. */
object Store {

    // Servidor de ativação (Cloudflare Worker) da Unlock BYD.
    const val SERVER = "https://euler-catalog.eulerfilhogontijodamata.workers.dev"
    // Desde a v13 o catálogo e a versão do app vêm do Worker (arquivos no R2), não mais do GitHub.
    const val CATALOG_URL = "$SERVER/catalog.json"
    const val SELF_VERSION_URL = "$SERVER/app_version.json"

    /** versionCode instalado deste app (enviado no heartbeat pro painel). */
    fun ownVersionCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    /** Última versão publicada do próprio app (versionCode, url do apk). */
    fun selfVersion(): Pair<Int, String> {
        return try {
            val j = httpGet(SELF_VERSION_URL) ?: return Pair(0, "")
            val o = JSONObject(j)
            Pair(o.optInt("versionCode", 0), o.optString("url", ""))
        } catch (e: Exception) { Pair(0, "") }
    }

    /** ID estável e curto do carro (derivado do ANDROID_ID). Ex.: A7F3-2901 */
    fun carId(ctx: Context): String {
        val aid = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) } catch (e: Exception) { null } ?: "unknown"
        val h = sha256hex(aid).uppercase()
        val s = if (h.length >= 8) h.substring(0, 8) else (h + "00000000").substring(0, 8)
        return s.substring(0, 4) + "-" + s.substring(4, 8)
    }

    private fun sha256hex(s: String): String {
        val b = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (x in b) sb.append(((x.toInt() and 0xff) + 0x100).toString(16).substring(1))
        return sb.toString()
    }

    // ---- onboarding (modelo + primeiro nome) ----
    internal fun prefs(ctx: Context) = ctx.getSharedPreferences("ubyd", Context.MODE_PRIVATE)
    fun isOnboarded(ctx: Context): Boolean = !(prefs(ctx).getString("firstname", "") ?: "").isNullOrEmpty()
    fun saveOnboarding(ctx: Context, model: String, name: String, grupo: String, ask: Boolean) {
        prefs(ctx).edit().putString("model", model).putString("firstname", name)
            .putString("grupo", grupo).putBoolean("ask", ask).apply()
    }
    fun clearOnboarding(ctx: Context) { prefs(ctx).edit().remove("model").remove("firstname").remove("grupo").remove("ask").apply() }

    /** App do catálogo (para auto-atualização): pacote, url e número de build. */
    data class CatApp(val pkg: String, val url: String, val build: Int)
    fun catalogApps(): List<CatApp> {
        val out = ArrayList<CatApp>()
        try {
            val arr = JSONObject(httpGet(CATALOG_URL) ?: return out).optJSONArray("apps") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.optString("package").trim(); val u = o.optString("url").trim()
                // O instalador do Android (lock_url) nunca é atualizado sozinho: só pela loja ou pela trava.
                if (p.isNotEmpty() && u.isNotEmpty() && o.optString("lock_url").trim().isEmpty()) out.add(CatApp(p, u, o.optInt("build", 0)))
            }
        } catch (e: Exception) {}
        return out
    }
    fun getBuild(ctx: Context, pkg: String): Int = prefs(ctx).getInt("b_$pkg", 0)
    fun setBuild(ctx: Context, pkg: String, n: Int) { prefs(ctx).edit().putInt("b_$pkg", n).apply() }
    fun getTries(ctx: Context, pkg: String, build: Int): Int = prefs(ctx).getInt("t_${pkg}_$build", 0)
    fun setTries(ctx: Context, pkg: String, build: Int, n: Int) { prefs(ctx).edit().putInt("t_${pkg}_$build", n).apply() }

    /** Guarda as últimas atualizações automáticas (pacote + hora), enviadas ao painel no heartbeat. */
    fun logAuto(ctx: Context, pkg: String) {
        val list = (prefs(ctx).getString("autolog", "") ?: "").split(';').filter { it.isNotBlank() }.toMutableList()
        list.add(0, pkg + "@" + System.currentTimeMillis())
        prefs(ctx).edit().putString("autolog", list.take(6).joinToString(";")).apply()
    }
    private fun autoLogJson(ctx: Context): String {
        val items = (prefs(ctx).getString("autolog", "") ?: "").split(';').filter { it.contains('@') }
        return items.joinToString(",", "[", "]") { val p = it.substringBefore('@'); val t = it.substringAfter('@')
            "{\"p\":\"" + p.replace("\"", "") + "\",\"t\":" + (t.toLongOrNull() ?: 0L) + "}" }
    }
    fun displayName(ctx: Context): String {
        val m = prefs(ctx).getString("model", "") ?: ""
        val n = prefs(ctx).getString("firstname", "") ?: ""
        return if (m.isNotEmpty() && n.isNotEmpty()) "$m — $n" else (Build.MODEL ?: "Veículo")
    }

    /** Registra/pinga o servidor. firstTime=true só no onboarding (cria o cadastro).
     *  status: active | pending | revoked | deleted | error */
    fun register(ctx: Context, firstTime: Boolean = false): RegResult {
        return try {
            val id = carId(ctx)
            val nome = displayName(ctx).replace("\"", "")
            val grupo = prefs(ctx).getString("grupo", "byd_simples")
            val ask = prefs(ctx).getBoolean("ask", false)
            val extra = if (firstTime) ",\"register\":true,\"grupo\":\"$grupo\",\"ask\":$ask" else ""
            val ver = ",\"vc\":${ownVersionCode(ctx)},\"sdk\":${Build.VERSION.SDK_INT},\"au\":${autoLogJson(ctx)}"
            val body = "{\"id\":\"$id\",\"nome\":\"$nome\"$extra$ver}"
            val resp = httpPostJson(SERVER + "/api/register", body) ?: return RegResult("error", false, "basico", grupo ?: "byd_simples")
            val o = JSONObject(resp)
            RegResult(o.optString("status", "error"), o.optBoolean("wipe", false), o.optString("plan", "basico"), o.optString("grupo", grupo ?: "byd_simples"))
        } catch (e: Exception) { RegResult("error", false, "basico", "byd_simples") }
    }

    /** Pede ao servidor permissão pra remover o Device Owner. true se autorizado (uso único). */
    fun requestRemoval(ctx: Context): Boolean {
        return try {
            val resp = httpPostJson(SERVER + "/api/removal", "{\"id\":\"${carId(ctx)}\"}") ?: return false
            JSONObject(resp).optBoolean("allowed", false)
        } catch (e: Exception) { false }
    }

    /** Pacotes listados no catálogo (os apps da loja). Não inclui o instalador do Android (entrada com lock_url). */
    fun catalogPackages(): List<String> {
        return try {
            val json = httpGet(CATALOG_URL) ?: return emptyList()
            val arr = JSONObject(json).optJSONArray("apps") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.optString("package").trim()
                if (p.isNotEmpty() && o.optString("lock_url").trim().isEmpty()) out.add(p)
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    // ---- troca do instalador do Android (Unlock <-> Package Installer Lock) ----
    /** Entrada do catálogo que tem "lock_url": pacote, link do Unlock (destrava) e do Lock (instalador original da BYD). */
    data class InstallerSwap(val pkg: String, val unlockUrl: String, val lockUrl: String)
    fun installerSwap(): InstallerSwap? {
        return try {
            val arr = JSONObject(httpGet(CATALOG_URL) ?: return null).optJSONArray("apps") ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lock = o.optString("lock_url").trim()
                if (lock.isNotEmpty()) return InstallerSwap(o.optString("package").trim(), o.optString("url").trim(), lock)
            }
            null
        } catch (e: Exception) { null }
    }

    /** Tamanho do APK instalado de um pacote (-1 se não instalado). */
    fun installedApkSize(ctx: Context, pkg: String): Long = try {
        java.io.File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir).length()
    } catch (e: Exception) { -1L }

    /** Tamanho de um arquivo no servidor (HEAD). -1 se falhar. */
    fun remoteSize(urlStr: String): Long {
        var url = urlStr; var redirects = 0
        return try {
            while (redirects < 6) {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"; instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 20000
                }
                val code = c.responseCode
                if (code in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); if (loc.isNullOrBlank()) return -1; url = loc; redirects++; continue }
                val n = if (code == 200) c.contentLengthLong else -1L
                c.disconnect(); return n
            }
            -1L
        } catch (e: Exception) { -1L }
    }

    /** true se o instalador instalado hoje é o Unlock (o que libera apps de terceiros). null = não deu pra saber. */
    fun installerUnlocked(ctx: Context, swap: InstallerSwap): Boolean? {
        val cur = installedApkSize(ctx, swap.pkg); if (cur <= 0) return null
        val unlock = remoteSize(swap.unlockUrl); if (unlock <= 0) return null
        return cur == unlock
    }

    /**
     * Carro travado: se o instalador atual é o Unlock, instala por cima o Package Installer Lock (o da BYD, que bloqueia
     * apps de terceiros). Só com Device Owner. Se já está travado (ou é outro instalador), não faz nada.
     */
    fun lockInstallerIfUnlocked(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return false
        val swap = installerSwap() ?: return false
        if (installerUnlocked(ctx, swap) != true) return false
        return Updater.installFromUrl(ctx, swap.lockUrl, swap.pkg)
    }

    /** Aplica o bloqueio de um carro revogado: troca o instalador pelo Lock e, se pedido, apaga os apps da loja. */
    fun applyRevoke(ctx: Context, wipe: Boolean) {
        try { lockInstallerIfUnlocked(ctx) } catch (e: Exception) {}
        if (wipe) try { uninstallAll(ctx) } catch (e: Exception) {}
    }

    /** Remove do carro os apps da loja já instalados (requer Device Owner). */
    fun uninstallAll(ctx: Context) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return
        val pm = ctx.packageManager
        for (pkg in catalogPackages()) {
            if (pkg == ctx.packageName) continue
            val installed = try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
            if (!installed) continue
            try {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(ctx, pkg.hashCode(),
                    Intent("com.euler.catalog.UNINSTALL_RESULT").setPackage(ctx.packageName), flags)
                pm.packageInstaller.uninstall(pkg, pi.intentSender)
            } catch (e: Exception) { /* ignora */ }
        }
    }

    // ---- HTTP ----
    fun httpGet(urlStr: String): String? {
        var url = urlStr; var redirects = 0
        while (redirects < 6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 20000
            }
            val code = c.responseCode
            if (code in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); if (loc.isNullOrBlank()) return null; url = loc; redirects++; continue }
            try { return if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
        }
        return null
    }

    fun httpPostJson(urlStr: String, body: String): String? {
        return try {
            val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = true
                connectTimeout = 15000; readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            c.disconnect()
            if (code in 200..299) txt else null
        } catch (e: Exception) { null }
    }
}
