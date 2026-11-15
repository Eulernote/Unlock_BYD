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

/** Lógica compartilhada: ativação no servidor + catálogo + remoção de apps. */
object Store {

    // Servidor de ativação (Cloudflare Worker) da Unlock BYD.
    const val SERVER = "https://euler-catalog.eulerfilhogontijodamata.workers.dev"
    const val CATALOG_URL = "https://raw.githubusercontent.com/Eulernote/euler-catalog/main/catalog.json"

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

    /** Registra/pinga o servidor. Retorna (status, wipe). status: active | pending | revoked | error */
    fun register(ctx: Context): Pair<String, Boolean> {
        return try {
            val id = carId(ctx)
            val nome = Build.MODEL?.replace("\"", "") ?: "Veículo"
            val body = "{\"id\":\"$id\",\"nome\":\"$nome\"}"
            val resp = httpPostJson(SERVER + "/api/register", body) ?: return Pair("error", false)
            val o = JSONObject(resp)
            Pair(o.optString("status", "error"), o.optBoolean("wipe", false))
        } catch (e: Exception) { Pair("error", false) }
    }

    /** Pacotes listados no catálogo (os apps da loja). */
    fun catalogPackages(): List<String> {
        return try {
            val json = httpGet(CATALOG_URL) ?: return emptyList()
            val arr = JSONObject(json).optJSONArray("apps") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i).optString("package").trim()
                if (p.isNotEmpty()) out.add(p)
            }
            out
        } catch (e: Exception) { emptyList() }
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
