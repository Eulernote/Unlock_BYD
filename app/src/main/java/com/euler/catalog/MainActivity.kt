package com.euler.catalog

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class MainActivity : Activity() {

    private val ACTION_INSTALL = "com.euler.catalog.INSTALL_STATUS"
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var container: LinearLayout

    private data class AppEntry(val name: String, val pkg: String, val url: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, CatalogAdminReceiver::class.java)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(44, 44, 44, 44) }
        scroll.addView(container)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL))

        scheduleDailyCheck()
        checkGate()
    }

    /** Agenda a checagem 1x/dia quando houver internet. */
    private fun scheduleDailyCheck() {
        try {
            val req = PeriodicWorkRequest.Builder(CheckWorker::class.java, 24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork("ubyd-check", ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (e: Exception) { /* ignora */ }
    }

    /** Consulta o servidor e decide: abrir a loja, travar ou travar+apagar. */
    private fun checkGate() {
        container.removeAllViews()
        container.addView(TextView(this).apply { text = "Verificando ativação…"; textSize = 15f; setPadding(0, 40, 0, 0) })
        Thread {
            val (status, wipe) = Store.register(this)
            runOnUiThread {
                when (status) {
                    "active" -> showCatalog()
                    "revoked" -> {
                        if (wipe) Thread { Store.uninstallAll(applicationContext) }.start()
                        showLocked("Acesso revogado", "Este catálogo foi desativado para este veículo. Fale com a Unlock BYD para reativar.")
                    }
                    "pending" -> showLocked("Aguardando ativação", "Envie o código abaixo para a Unlock BYD liberar este veículo.")
                    else -> showLocked("Sem conexão", "Não foi possível verificar a ativação. Confira a internet do carro e tente de novo.")
                }
            }
        }.start()
    }

    private fun showLocked(title: String, msg: String) {
        container.removeAllViews()
        container.gravity = Gravity.CENTER_HORIZONTAL
        container.addView(TextView(this).apply { text = title; textSize = 22f; setPadding(0, 60, 0, 10) })
        container.addView(TextView(this).apply { text = msg; textSize = 14f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 30) })
        container.addView(TextView(this).apply { text = "Código do veículo"; textSize = 12f; setPadding(0, 10, 0, 6) })
        container.addView(TextView(this).apply {
            text = Store.carId(this@MainActivity); textSize = 30f; setPadding(0, 0, 0, 36)
            typeface = android.graphics.Typeface.MONOSPACE
        })
        container.addView(Button(this).apply { text = "Verificar novamente"; setOnClickListener { container.gravity = Gravity.NO_GRAVITY; checkGate() } })
    }

    // ---------------- Catálogo (quando ATIVO) ----------------

    private fun showCatalog() {
        container.removeAllViews()
        container.gravity = Gravity.NO_GRAVITY
        container.addView(TextView(this).apply {
            text = if (dpm.isDeviceOwnerApp(packageName)) "Catálogo Unlock BYD" else "ATENÇÃO: não é Device Owner — rode o dpm set-device-owner."
            textSize = 16f; setPadding(0, 0, 0, 16)
        })
        container.addView(TextView(this).apply { text = "Carregando apps…"; setPadding(0, 10, 0, 0) })
        Thread {
            val entries = try { parseCatalog(Store.httpGet(Store.CATALOG_URL) ?: "") } catch (e: Exception) { null }
            runOnUiThread { renderCatalog(entries) }
        }.start()
    }

    private fun renderCatalog(entries: List<AppEntry>?) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = if (dpm.isDeviceOwnerApp(packageName)) "Catálogo Unlock BYD" else "ATENÇÃO: não é Device Owner."
            textSize = 16f; setPadding(0, 0, 0, 16)
        })
        if (entries == null) {
            container.addView(TextView(this).apply { text = "Não consegui carregar a lista. Verifique a internet." })
            container.addView(Button(this).apply { text = "Tentar de novo"; setOnClickListener { showCatalog() } })
            return
        }
        if (entries.isEmpty()) { container.addView(TextView(this).apply { text = "Catálogo vazio." }); return }

        container.addView(Button(this).apply {
            text = "Instalar TODOS os que faltam"
            setOnClickListener { entries.filter { !isInstalled(it.pkg) }.forEach { installFromWeb(it) } }
        })
        for (e in entries) {
            val inst = isInstalled(e.pkg)
            container.addView(TextView(this).apply { text = "${e.name}  (${e.pkg}) — " + if (inst) "instalado" else "disponível"; setPadding(0, 16, 0, 2) })
            container.addView(Button(this).apply { text = (if (inst) "Reinstalar " else "Instalar ") + e.name; setOnClickListener { installFromWeb(e) } })
        }
    }

    private fun isInstalled(pkg: String): Boolean = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun parseCatalog(json: String): List<AppEntry> {
        val arr = JSONObject(json).optJSONArray("apps") ?: return emptyList()
        val out = ArrayList<AppEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val n = o.optString("name").trim(); val p = o.optString("package").trim(); val u = o.optString("url").trim()
            if (n.isNotEmpty() && p.isNotEmpty() && u.isNotEmpty()) out.add(AppEntry(n, p, u))
        }
        return out
    }

    private fun installFromWeb(e: AppEntry) {
        toast("Baixando ${e.name}…")
        Thread {
            val dl = File(cacheDir, e.pkg + ".dl")
            val ok = try { download(e.url, dl) } catch (ex: Exception) { false }
            if (!ok || dl.length() == 0L) { runOnUiThread { toast("Falha ao baixar ${e.name}") }; return@Thread }
            val splits = try { extractSplits(dl, e.pkg) } catch (ex: Exception) { null }
            runOnUiThread {
                try {
                    if (splits == null) installSession(listOf(dl))
                    else if (splits.isEmpty()) toast("Pacote de ${e.name} sem APK dentro")
                    else installSession(splits)
                } catch (ex: Exception) { toast("Falha ao instalar ${e.name}: ${ex.message}") }
            }
        }.start()
    }

    private fun download(urlStr: String, dest: File): Boolean {
        var url = urlStr; var redirects = 0
        while (redirects < 6) {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 300000
            }
            val code = c.responseCode
            if (code in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); if (loc.isNullOrBlank()) return false; url = loc; redirects++; continue }
            if (code != 200) { c.disconnect(); return false }
            try { c.inputStream.use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }; return true } finally { c.disconnect() }
        }
        return false
    }

    private fun extractSplits(file: File, pkg: String): List<File>? {
        ZipFile(file).use { zf ->
            val apkEntries = zf.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk", true) && !it.name.contains("standalones/") }
            if (apkEntries.isEmpty()) return null
            val outDir = File(cacheDir, "sp_$pkg").apply { deleteRecursively(); mkdirs() }
            var universal: File? = null
            val splits = ArrayList<File>()
            for (entry in apkEntries) {
                val base = entry.name.substringAfterLast('/')
                val out = File(outDir, base)
                zf.getInputStream(entry).use { inp -> out.outputStream().use { inp.copyTo(it) } }
                if (base.equals("universal.apk", true)) universal = out
                splits.add(out)
            }
            return if (universal != null) listOf(universal!!) else splits
        }
    }

    private fun installSession(apks: List<File>) {
        val pi = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pi.createSession(params)
        val session = pi.openSession(sessionId)
        var i = 0
        for (f in apks) {
            session.openWrite("split_$i.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            i++
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(this, sessionId, Intent(ACTION_INSTALL).setPackage(packageName), flags)
        session.commit(pending.intentSender)
        session.close()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_SUCCESS -> {
                    val pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                    if (pkg != null) grantAll(pkg)
                    toast("Instalado: $pkg — permissões concedidas")
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                else -> i?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { if (it.isNotEmpty()) toast("Erro: $it") }
            }
        }
    }

    private fun grantAll(pkg: String) {
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val perms = try { packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions } catch (e: Exception) { null } ?: return
        for (p in perms) try { dpm.setPermissionGrantState(admin, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) } catch (e: Exception) {}
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { super.onDestroy(); try { unregisterReceiver(statusReceiver) } catch (e: Exception) {} }
}
