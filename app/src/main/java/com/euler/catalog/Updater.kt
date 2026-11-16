package com.euler.catalog

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Atualização automática silenciosa (Device Owner), usada pelo CheckWorker. */
object Updater {

    fun isUnmetered(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        !cm.isActiveNetworkMetered
    } catch (e: Exception) { false }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun firstTimeToday(ctx: Context): Boolean = Store.prefs(ctx).getString("lastUpdDay", "") != today()
    fun markDoneToday(ctx: Context) { Store.prefs(ctx).edit().putString("lastUpdDay", today()).apply() }

    private fun installedVc(ctx: Context, pkg: String): Long = try {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (e: Exception) { -1 }

    private fun isInstalled(ctx: Context, pkg: String) = installedVc(ctx, pkg) >= 0

    /** Atualiza o próprio Unlock BYD se houver versão nova (qualquer rede). */
    fun selfUpdateIfNeeded(ctx: Context) {
        val sv = Store.selfVersion()           // (versionCode, url)
        if (sv.first.toLong() > installedVc(ctx, ctx.packageName) && sv.second.isNotEmpty())
            silentInstall(ctx, sv.second, ctx.packageName)
    }

    /** Atualiza os outros apps instalados cujo build no catálogo é maior que o já instalado. */
    fun updateInstalledApps(ctx: Context) {
        for (a in Store.catalogApps()) {
            if (a.pkg == ctx.packageName) continue
            if (!isInstalled(ctx, a.pkg)) continue
            if (a.build > Store.getBuild(ctx, a.pkg)) {
                if (silentInstall(ctx, a.url, a.pkg)) Store.setBuild(ctx, a.pkg, a.build)
            }
        }
    }

    // ---------- download + instalação silenciosa ----------
    private fun silentInstall(ctx: Context, url: String, pkg: String): Boolean {
        val dl = File(ctx.cacheDir, "up_$pkg.dl")
        val spDir = File(ctx.cacheDir, "up_sp_$pkg")
        try {
            if (!download(url, dl) || dl.length() == 0L) return false
            val apks = extractSplits(ctx, dl, pkg, spDir) ?: listOf(dl)
            if (apks.isEmpty()) return false
            val ok = commitInstall(ctx, apks)
            if (ok) grantAll(ctx, pkg)
            return ok
        } catch (e: Exception) {
            return false
        } finally {
            try { dl.delete() } catch (e: Exception) {}
            try { spDir.deleteRecursively() } catch (e: Exception) {}
        }
    }

    private fun commitInstall(ctx: Context, apks: List<File>): Boolean {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val total = apks.sumOf { it.length() }
        if (total > 0) try { params.setSize(total) } catch (e: Exception) {}
        val sid = pi.createSession(params)
        val session = pi.openSession(sid)
        var i = 0
        for (f in apks) { session.openWrite("s$i.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; session.fsync(out) }; i++ }

        val action = "com.euler.catalog.UPD_DONE.$sid"
        val latch = CountDownLatch(1); val status = intArrayOf(-99)
        val rc = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { status[0] = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -99) ?: -99; latch.countDown() }
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rc, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(rc, IntentFilter(action))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(ctx, sid, Intent(action).setPackage(ctx.packageName), flags)
        session.commit(pending.intentSender); session.close()
        val done = latch.await(5, TimeUnit.MINUTES)
        try { ctx.unregisterReceiver(rc) } catch (e: Exception) {}
        return done && status[0] == PackageInstaller.STATUS_SUCCESS
    }

    private fun grantAll(ctx: Context, pkg: String) {
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(ctx.packageName)) return
            val admin = ComponentName(ctx, CatalogAdminReceiver::class.java)
            val perms = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions ?: return
            for (p in perms) try { dpm.setPermissionGrantState(admin, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    /** Mesma regra do app: se já é APK normal (AndroidManifest.xml na raiz), instala direto. */
    private fun extractSplits(ctx: Context, file: File, pkg: String, outDir: File): List<File>? {
        ZipFile(file).use { zf ->
            val entries = zf.entries().toList().filter { !it.isDirectory }
            if (entries.any { it.name == "AndroidManifest.xml" }) return null
            var apkEntries = entries.filter { it.name.endsWith(".apk", true) && !it.name.contains("/") }
            if (apkEntries.isEmpty())
                apkEntries = entries.filter { it.name.endsWith(".apk", true) && !it.name.contains("assets/") && !it.name.contains("standalones/") }
            if (apkEntries.isEmpty()) return null
            outDir.deleteRecursively(); outDir.mkdirs()
            var universal: File? = null; val splits = ArrayList<File>()
            for (entry in apkEntries) {
                val base = entry.name.substringAfterLast('/'); val out = File(outDir, base)
                zf.getInputStream(entry).use { inp -> out.outputStream().use { inp.copyTo(it) } }
                if (base.equals("universal.apk", true)) universal = out
                splits.add(out)
            }
            return if (universal != null) listOf(universal!!) else splits
        }
    }

    private fun download(urlStr: String, dest: File): Boolean {
        var url = urlStr; var redirects = 0
        while (redirects < 6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 300000 }
            val code = c.responseCode
            if (code in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); if (loc.isNullOrBlank()) return false; url = loc; redirects++; continue }
            if (code != 200) { c.disconnect(); return false }
            try {
                c.inputStream.use { inp -> dest.outputStream().use { out -> val b = ByteArray(64 * 1024); while (true) { val r = inp.read(b); if (r < 0) break; out.write(b, 0, r) }; out.flush() } }
                return true
            } catch (e: Exception) { return false } finally { c.disconnect() }
        }
        return false
    }
}
