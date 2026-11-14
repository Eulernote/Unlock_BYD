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
import android.os.Environment
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Catálogo online de apps curados.
 * Ao abrir, baixa a lista (catalog.json), mostra os apps e instala com um clique:
 * baixa o pacote, instala em silêncio (Device Owner) e concede as permissões.
 * Aceita .apk (único) e .xapk/.apks (base + splits) — detecta pelo conteúdo.
 * Requer que o app seja DEVICE OWNER e que o carro tenha internet.
 *
 * Modelo de catalog.json:
 * { "apps": [ { "name": "Waze", "package": "com.waze",
 *               "url": "https://.../waze.xapk" } ] }
 */
class MainActivity : Activity() {

    companion object {
        const val CATALOG_URL = "https://raw.githubusercontent.com/Eulernote/euler-catalog/main/catalog.json"
    }

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
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        scroll.addView(container)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL))
        }
        refresh()
    }

    private fun isInstalled(pkg: String): Boolean =
        try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun refresh() {
        container.removeAllViews()
        container.addView(header())
        container.addView(TextView(this).apply { text = "Carregando catálogo…"; setPadding(0, 24, 0, 0) })

        Thread {
            val entries = try { parseCatalog(httpGet(CATALOG_URL)) } catch (e: Exception) { null }
            runOnUiThread {
                container.removeAllViews()
                container.addView(header())
                if (entries == null) {
                    container.addView(TextView(this).apply {
                        text = "Não consegui baixar a lista.\nVerifique a internet do carro.\n\nURL: $CATALOG_URL"
                        setPadding(0, 24, 0, 16)
                    })
                    container.addView(Button(this).apply { text = "Tentar de novo"; setOnClickListener { refresh() } })
                } else render(entries)
                addOwnerMaintenance()
            }
        }.start()
    }

    private fun header(): TextView = TextView(this).apply {
        text = if (dpm.isDeviceOwnerApp(packageName)) "Device Owner: OK (instalação silenciosa ativa)"
               else "ATENÇÃO: não é Device Owner — a instalação vai pedir confirmação e não concede permissões sozinha. Rode o dpm set-device-owner."
        setPadding(0, 0, 0, 16)
    }

    private fun render(entries: List<AppEntry>) {
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply { text = "Catálogo vazio. Adicione apps no catalog.json." })
            return
        }
        container.addView(Button(this).apply {
            text = "Baixar e instalar TODOS os que faltam"
            setOnClickListener { entries.filter { !isInstalled(it.pkg) }.forEach { installFromWeb(it) } }
        })
        container.addView(TextView(this).apply { text = ""; setPadding(0, 16, 0, 0) })

        for (e in entries) {
            val installed = isInstalled(e.pkg)
            container.addView(TextView(this).apply {
                text = "${e.name}  (${e.pkg})  — " + if (installed) "instalado" else "disponível"
                setPadding(0, 16, 0, 2)
            })
            container.addView(Button(this).apply {
                text = if (installed) "Reinstalar ${e.name}" else "Instalar ${e.name}"
                setOnClickListener { installFromWeb(e) }
            })
        }
    }

    private fun addOwnerMaintenance() {
        if (!dpm.isDeviceOwnerApp(packageName)) return
        container.addView(TextView(this).apply { text = ""; setPadding(0, 40, 0, 0) })
        container.addView(Button(this).apply {
            text = "(manutenção) Liberar Device Owner"
            setOnClickListener {
                try { dpm.clearDeviceOwnerApp(packageName); toast("Device Owner removido"); refresh() }
                catch (e: Exception) { toast("Falha: ${e.message}") }
            }
        })
    }

    /** Baixa o pacote (apk/xapk/apks) e instala, em background. */
    private fun installFromWeb(e: AppEntry) {
        toast("Baixando ${e.name}…")
        Thread {
            val dl = File(cacheDir, e.pkg + ".dl")
            val ok = try { httpDownload(e.url, dl) } catch (ex: Exception) { false }
            if (!ok || dl.length() == 0L) { runOnUiThread { toast("Falha ao baixar ${e.name}") }; return@Thread }

            // Detecta formato pelo conteúdo: se o zip contém .apk internos é bundle (xapk/apks).
            val splits = try { extractSplits(dl, e.pkg) } catch (ex: Exception) { null }
            runOnUiThread {
                try {
                    if (splits == null) installSession(listOf(dl))   // apk único
                    else if (splits.isEmpty()) toast("Pacote de ${e.name} sem APK dentro")
                    else installSession(splits)                      // base + splits
                } catch (ex: Exception) { toast("Falha ao instalar ${e.name}: ${ex.message}") }
            }
        }.start()
    }

    /**
     * Se o arquivo é um bundle (xapk/apks), extrai os .apk internos para o cache e
     * retorna a lista; copia OBB se houver. Retorna null se for um APK único.
     */
    private fun extractSplits(file: File, pkg: String): List<File>? {
        ZipFile(file).use { zf ->
            val apkEntries = zf.entries().toList().filter {
                !it.isDirectory && it.name.endsWith(".apk", true) && !it.name.contains("standalones/")
            }
            if (apkEntries.isEmpty()) return null  // APK único (não contém .apk internos)

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
            // OBB (best-effort; pode ser bloqueado em Android novo)
            zf.entries().toList().filter {
                !it.isDirectory && (it.name.endsWith(".obb", true) || it.name.contains("Android/obb/"))
            }.forEach { entry ->
                try {
                    val dir = File(Environment.getExternalStorageDirectory(), "Android/obb/$pkg").apply { mkdirs() }
                    zf.getInputStream(entry).use { inp ->
                        File(dir, entry.name.substringAfterLast('/')).outputStream().use { inp.copyTo(it) }
                    }
                } catch (e: Exception) { /* ignora falha de OBB */ }
            }
            // universal.apk basta sozinho; senão instala todos os splits juntos
            return if (universal != null) listOf(universal!!) else splits
        }
    }

    /** Instala um ou vários APKs (splits) numa única sessão. */
    private fun installSession(apks: List<File>) {
        val pi = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pi.createSession(params)
        val session = pi.openSession(sessionId)
        var i = 0
        for (f in apks) {
            session.openWrite("split_$i.apk", 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            i++
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(
            this, sessionId, Intent(ACTION_INSTALL).setPackage(packageName), flags
        )
        session.commit(pendingIntent.intentSender)
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
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                else -> toast("Erro: ${i?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }

    private fun grantAll(pkg: String) {
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val perms = try {
            packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions
        } catch (e: Exception) { null } ?: return
        for (p in perms) {
            try { dpm.setPermissionGrantState(admin, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
            catch (e: Exception) { /* não-runtime: ignora */ }
        }
    }

    // ---- HTTP simples (sem dependências) ----

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 15000; readTimeout = 20000
        }
        try { return conn.inputStream.bufferedReader().use { it.readText() } }
        finally { conn.disconnect() }
    }

    private fun httpDownload(urlStr: String, dest: File): Boolean {
        var url = urlStr; var redirects = 0
        while (redirects < 6) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 300000
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location"); conn.disconnect()
                if (loc.isNullOrBlank()) return false
                url = loc; redirects++; continue
            }
            if (code != 200) { conn.disconnect(); return false }
            try {
                conn.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
                return true
            } finally { conn.disconnect() }
        }
        return false
    }

    private fun parseCatalog(json: String): List<AppEntry> {
        val arr = JSONObject(json).optJSONArray("apps") ?: return emptyList()
        val out = ArrayList<AppEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").trim()
            val pkg = o.optString("package").trim()
            val url = o.optString("url").trim()
            if (name.isNotEmpty() && pkg.isNotEmpty() && url.isNotEmpty()) out.add(AppEntry(name, pkg, url))
        }
        return out
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }
}
