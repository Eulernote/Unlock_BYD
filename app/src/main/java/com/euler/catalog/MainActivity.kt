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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class MainActivity : Activity() {

    private val ACTION_INSTALL = "com.euler.catalog.INSTALL_STATUS"
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var container: LinearLayout
    private val iconCache = HashMap<String, Bitmap>()
    private var selectedModel: String? = null
    private var continueBtn: Button? = null
    private val modelCards = ArrayList<Pair<LinearLayout, String>>()
    private val MODELS = listOf(
        "Dolphin Mini" to true, "Dolphin GS" to true, "Dolphin SE" to true, "Dolphin Plus" to true,
        "King" to true, "Yuan Pro" to true, "Yuan Plus" to true, "Song Pro" to true, "Song Plus" to true,
        "Song Premium" to true, "Atto 2" to true, "Atto 8" to true, "Shark" to true, "Seal" to true,
        "Sealion" to false, "Geely EX2 Pro" to true, "Geely EX2 Max" to true, "Tiggo 7" to true,
        "Tiggo 8" to true, "Omoda 5" to true, "Omoda E5" to true, "Jaecoo 7" to true
    )

    // paleta
    private val BG = 0xFF0A0C10.toInt(); private val SURFACE = 0xFF141922.toInt()
    private val SURFACE2 = 0xFF1A2029.toInt(); private val BORDER = 0xFF232B38.toInt()
    private val TEXT = 0xFFE8ECF3.toInt(); private val MUTED = 0xFF8793A6.toInt()
    private val ACCENT = 0xFF2BB0A6.toInt(); private val GREEN = 0xFF27C08A.toInt(); private val RED = 0xFFEF5350.toInt()

    private data class AppEntry(val name: String, val pkg: String, val url: String, val icon: String, val desc: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, CatalogAdminReceiver::class.java)

        val scroll = ScrollView(this); scroll.setBackgroundColor(BG)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(40)) }
        scroll.addView(container); setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL))

        scheduleDailyCheck()
        if (Store.isOnboarded(this)) checkGate() else showOnboarding()
    }

    // ---------- helpers de UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable {
        val g = GradientDrawable(); g.setColor(color); g.cornerRadius = dp(r).toFloat()
        if (stroke > 0) g.setStroke(dp(stroke), strokeColor); return g
    }
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this); t.text = text; t.textSize = size; t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD); return t
    }
    private fun headerView(subtitle: String): LinearLayout {
        val h = LinearLayout(this); h.orientation = LinearLayout.HORIZONTAL; h.gravity = Gravity.CENTER_VERTICAL
        h.setPadding(0, 0, 0, dp(18))
        val logo = ImageView(this); logo.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        logo.background = rounded(0xFFFFFFFF.toInt(), 12); logo.setPadding(dp(5), dp(5), dp(5), dp(5)); logo.setImageResource(R.mipmap.ic_launcher)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(dp(12), 0, 0, 0)
        col.addView(tv("Unlock BYD", 17f, TEXT, true)); col.addView(tv(subtitle, 12f, MUTED))
        h.addView(logo); h.addView(col); return h
    }
    private fun styleBtn(b: Button, bg: Int, fg: Int) {
        b.background = rounded(bg, 9); b.setTextColor(fg); b.isAllCaps = false; b.textSize = 13f
        b.setPadding(dp(16), dp(8), dp(16), dp(8)); b.minHeight = 0; b.minimumHeight = 0
    }

    private fun scheduleDailyCheck() {
        try {
            val req = PeriodicWorkRequest.Builder(CheckWorker::class.java, 24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork("ubyd-check", ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (e: Exception) {}
    }

    private fun checkGate() {
        container.removeAllViews()
        container.addView(headerView("Verificando ativação…"))
        Thread {
            val (status, wipe) = Store.register(this)
            runOnUiThread {
                when (status) {
                    "active" -> showCatalog()
                    "revoked" -> { if (wipe) Thread { Store.uninstallAll(applicationContext) }.start()
                        showLocked("Acesso revogado", "Este catálogo foi desativado para este veículo. Fale com a Unlock BYD para reativar.") }
                    "pending" -> showLocked("Aguardando ativação", "Envie o código abaixo para a Unlock BYD liberar este veículo.")
                    else -> showLocked("Sem conexão", "Não foi possível verificar a ativação. Confira a internet do carro e tente de novo.")
                }
            }
        }.start()
    }

    private fun showLocked(title: String, msg: String) {
        container.removeAllViews()
        container.addView(headerView("Loja do veículo"))
        val card = LinearLayout(this); card.orientation = LinearLayout.VERTICAL; card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = rounded(SURFACE, 16, 1, BORDER); card.setPadding(dp(22), dp(30), dp(22), dp(30))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(24); card.layoutParams = lp
        card.addView(tv(title, 20f, TEXT, true).apply { gravity = Gravity.CENTER })
        card.addView(tv(msg, 13.5f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(24)) })
        card.addView(tv("Código do veículo", 12f, MUTED))
        card.addView(tv(Store.carId(this), 30f, ACCENT, true).apply { setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setPadding(0, dp(4), 0, dp(26)) })
        val b = Button(this); b.text = "Verificar novamente"; styleBtn(b, ACCENT, 0xFF04201D.toInt()); b.setOnClickListener { checkGate() }
        card.addView(b)
        container.addView(card)
    }

    // ---------- Onboarding (primeiro uso) ----------
    private fun showOnboarding() {
        container.removeAllViews(); modelCards.clear(); selectedModel = null
        container.addView(headerView("Bem-vindo"))
        container.addView(tv("Informe o modelo do seu veículo", 17f, TEXT, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        container.addView(tv("Deslize para o lado e toque no modelo.", 12.5f, MUTED).apply { setPadding(0, 0, 0, dp(14)) })

        val sc = HorizontalScrollView(this); sc.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for ((name, avail) in MODELS) row.addView(modelCard(name, avail))
        sc.addView(row); container.addView(sc)

        val cont = Button(this); cont.text = "Continuar"; styleBtn(cont, ACCENT, 0xFF04201D.toInt()); cont.isEnabled = false; cont.alpha = 0.45f
        val clp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); clp.topMargin = dp(22); cont.layoutParams = clp
        cont.setOnClickListener { selectedModel?.let { showNameStep(it) } }
        continueBtn = cont; container.addView(cont)
    }

    private fun modelCard(name: String, avail: Boolean): LinearLayout {
        val card = LinearLayout(this); card.orientation = LinearLayout.VERTICAL; card.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(dp(130), dp(90)); lp.rightMargin = dp(10); card.layoutParams = lp
        card.setPadding(dp(10), dp(10), dp(10), dp(10)); card.background = rounded(SURFACE, 14, 1, BORDER)
        if (avail) {
            card.addView(tv(name, 14f, TEXT, true).apply { gravity = Gravity.CENTER })
            card.setOnClickListener { selectModel(name) }
            modelCards.add(card to name)
        } else {
            card.alpha = 0.45f
            card.addView(tv(name, 14f, MUTED, true).apply { gravity = Gravity.CENTER })
            card.addView(tv("Indisponível", 11f, RED).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        }
        return card
    }

    private fun selectModel(name: String) {
        selectedModel = name
        for ((v, n) in modelCards) {
            v.background = if (n == name) rounded(ACCENT, 14, 2, ACCENT) else rounded(SURFACE, 14, 1, BORDER)
            (v.getChildAt(0) as? TextView)?.setTextColor(if (n == name) 0xFF04201D.toInt() else TEXT)
        }
        continueBtn?.isEnabled = true; continueBtn?.alpha = 1f
    }

    private fun showNameStep(model: String) {
        container.removeAllViews()
        container.addView(headerView("Bem-vindo"))
        container.addView(tv("Modelo selecionado", 12f, MUTED).apply { setPadding(0, dp(8), 0, dp(2)) })
        container.addView(tv(model, 16f, ACCENT, true).apply { setPadding(0, 0, 0, dp(18)) })
        container.addView(tv("Digite o seu primeiro nome", 15f, TEXT, true))
        val et = EditText(this); et.hint = "Seu primeiro nome"; et.setHintTextColor(MUTED); et.setTextColor(TEXT)
        et.background = rounded(SURFACE, 10, 1, BORDER); et.setPadding(dp(12), dp(12), dp(12), dp(12)); et.setSingleLine()
        val elp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); elp.topMargin = dp(8); et.layoutParams = elp
        container.addView(et)
        val send = Button(this); send.text = "Enviar solicitação"; styleBtn(send, ACCENT, 0xFF04201D.toInt())
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); slp.topMargin = dp(18); send.layoutParams = slp
        send.setOnClickListener {
            val nm = et.text.toString().trim()
            if (nm.isEmpty()) { toast("Digite seu primeiro nome"); return@setOnClickListener }
            Store.saveOnboarding(this, model, nm); checkGate()
        }
        container.addView(send)
        val back = Button(this); back.text = "Voltar"; styleBtn(back, SURFACE2, MUTED); back.background = rounded(SURFACE2, 9, 1, BORDER); back.setTextColor(MUTED)
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); blp.topMargin = dp(10); back.layoutParams = blp
        back.setOnClickListener { showOnboarding() }
        container.addView(back)
    }

    // ---------- Catálogo (ATIVO) ----------
    private fun showCatalog() {
        container.removeAllViews()
        container.addView(headerView("Loja do veículo"))
        container.addView(tv("Carregando apps…", 14f, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        Thread {
            val entries = try { parseCatalog(Store.httpGet(Store.CATALOG_URL) ?: "") } catch (e: Exception) { null }
            runOnUiThread { renderCatalog(entries) }
        }.start()
    }

    private fun renderCatalog(entries: List<AppEntry>?) {
        container.removeAllViews()
        container.addView(headerView("Loja do veículo"))
        if (!dpm.isDeviceOwnerApp(packageName))
            container.addView(tv("Atenção: não é Device Owner — rode o dpm set-device-owner.", 12f, RED).apply { setPadding(0, 0, 0, dp(8)) })
        if (entries == null) {
            container.addView(tv("Não consegui carregar a lista. Verifique a internet.", 14f, MUTED).apply { setPadding(0, dp(10), 0, dp(12)) })
            val b = Button(this); b.text = "Tentar de novo"; styleBtn(b, ACCENT, 0xFF04201D.toInt()); b.setOnClickListener { showCatalog() }; container.addView(b); return
        }
        if (entries.isEmpty()) { container.addView(tv("Catálogo vazio.", 14f, MUTED)); return }

        val all = Button(this); all.text = "Instalar todos os que faltam"; styleBtn(all, SURFACE2, TEXT)
        all.background = rounded(SURFACE2, 10, 1, BORDER); all.setTextColor(TEXT)
        val alp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); alp.topMargin = dp(4); all.layoutParams = alp
        all.setOnClickListener { entries.filter { !isInstalled(it.pkg) }.forEach { installFromWeb(it) } }
        container.addView(all)

        for (e in entries) container.addView(appCard(e))

        if (dpm.isDeviceOwnerApp(packageName)) {
            val rm = Button(this); rm.text = "Remover Device Owner"; styleBtn(rm, SURFACE2, MUTED)
            rm.background = rounded(SURFACE2, 9, 1, BORDER); rm.setTextColor(MUTED)
            val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); rlp.topMargin = dp(30); rm.layoutParams = rlp
            rm.setOnClickListener { requestRemoval() }
            container.addView(rm)
            container.addView(tv("Requer autorização no painel da Unlock BYD.", 11f, MUTED).apply { setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER })
        }
    }

    private fun requestRemoval() {
        toast("Verificando autorização…")
        Thread {
            val allowed = Store.requestRemoval(this)
            runOnUiThread {
                if (allowed) {
                    try { dpm.clearDeviceOwnerApp(packageName); toast("Device Owner removido"); checkGate() }
                    catch (e: Exception) { toast("Falha ao remover: ${e.message}") }
                } else toast("Remoção não autorizada. Solicite no painel.")
            }
        }.start()
    }

    private fun appCard(e: AppEntry): LinearLayout {
        val card = LinearLayout(this); card.orientation = LinearLayout.HORIZONTAL; card.gravity = Gravity.CENTER_VERTICAL
        card.background = rounded(SURFACE, 14, 1, BORDER); card.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(10); card.layoutParams = lp

        val icon = ImageView(this); icon.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        icon.background = rounded(SURFACE2, 12, 1, BORDER); icon.setPadding(dp(8), dp(8), dp(8), dp(8)); setIcon(icon, e)

        val info = LinearLayout(this); info.orientation = LinearLayout.VERTICAL
        val ilp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); ilp.leftMargin = dp(12); ilp.rightMargin = dp(10); info.layoutParams = ilp
        info.addView(tv(e.name, 14.5f, TEXT, true))
        if (e.desc.isNotEmpty()) info.addView(tv(e.desc, 12f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        val inst = isInstalled(e.pkg)
        info.addView(tv(if (inst) "Instalado" else "Disponível", 11f, if (inst) GREEN else MUTED).apply { setPadding(0, dp(4), 0, 0) })

        val btn = Button(this); btn.text = if (inst) "Reinstalar" else "Instalar"
        styleBtn(btn, if (inst) SURFACE2 else ACCENT, if (inst) TEXT else 0xFF04201D.toInt())
        if (inst) btn.background = rounded(SURFACE2, 9, 1, BORDER)
        btn.setOnClickListener { installFromWeb(e) }

        card.addView(icon); card.addView(info); card.addView(btn); return card
    }

    private fun setIcon(iv: ImageView, e: AppEntry) {
        if (isInstalled(e.pkg)) { try { iv.setImageDrawable(packageManager.getApplicationIcon(e.pkg)); iv.setPadding(dp(4), dp(4), dp(4), dp(4)); return } catch (ex: Exception) {} }
        iv.setImageResource(R.drawable.ic_app)
        val url = e.icon
        if (url.isNotEmpty()) {
            iconCache[url]?.let { iv.setImageBitmap(it); iv.setPadding(dp(4), dp(4), dp(4), dp(4)); return }
            Thread {
                try {
                    val b = downloadBytes(url)
                    if (b != null) { val bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
                        if (bmp != null) { iconCache[url] = bmp; runOnUiThread { iv.setImageBitmap(bmp); iv.setPadding(dp(4), dp(4), dp(4), dp(4)) } } }
                } catch (ex: Exception) {}
            }.start()
        }
    }

    private fun isInstalled(pkg: String): Boolean = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun parseCatalog(json: String): List<AppEntry> {
        val arr = JSONObject(json).optJSONArray("apps") ?: return emptyList()
        val out = ArrayList<AppEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val n = o.optString("name").trim(); val p = o.optString("package").trim(); val u = o.optString("url").trim()
            if (n.isNotEmpty() && p.isNotEmpty() && u.isNotEmpty())
                out.add(AppEntry(n, p, u, o.optString("icon").trim(), o.optString("desc").trim()))
        }
        return out
    }

    // ---------- download / install ----------
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
            val c = (URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 300000 }
            val code = c.responseCode
            if (code in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); if (loc.isNullOrBlank()) return false; url = loc; redirects++; continue }
            if (code != 200) { c.disconnect(); return false }
            try { c.inputStream.use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }; return true } finally { c.disconnect() }
        }
        return false
    }

    private fun downloadBytes(urlStr: String): ByteArray? {
        var url = urlStr; var redirects = 0
        while (redirects < 6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 12000; readTimeout = 20000 }
            val code = c.responseCode
            if (code in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); if (loc.isNullOrBlank()) return null; url = loc; redirects++; continue }
            if (code != 200) { c.disconnect(); return null }
            try { return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
        }
        return null
    }

    private fun extractSplits(file: File, pkg: String): List<File>? {
        ZipFile(file).use { zf ->
            val apkEntries = zf.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk", true) && !it.name.contains("standalones/") }
            if (apkEntries.isEmpty()) return null
            val outDir = File(cacheDir, "sp_$pkg").apply { deleteRecursively(); mkdirs() }
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

    private fun installSession(apks: List<File>) {
        val pi = packageManager.packageInstaller
        val sessionId = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val session = pi.openSession(sessionId); var i = 0
        for (f in apks) { session.openWrite("split_$i.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; session.fsync(out) }; i++ }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(this, sessionId, Intent(ACTION_INSTALL).setPackage(packageName), flags)
        session.commit(pending.intentSender); session.close()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_SUCCESS -> {
                    val pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                    if (pkg != null) grantAll(pkg)
                    toast("Instalado: $pkg — permissões concedidas")
                    if (dpm.isDeviceOwnerApp(packageName)) checkGateSilent()
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

    /** Após instalar, atualiza os cards (sem recarregar o header "Verificando"). */
    private fun checkGateSilent() { showCatalog() }

    private fun grantAll(pkg: String) {
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val perms = try { packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions } catch (e: Exception) { null } ?: return
        for (p in perms) try { dpm.setPermissionGrantState(admin, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) } catch (e: Exception) {}
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { super.onDestroy(); try { unregisterReceiver(statusReceiver) } catch (e: Exception) {} }
}
