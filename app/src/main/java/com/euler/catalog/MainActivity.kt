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
import android.os.Handler
import android.os.Looper
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
    private val poller = Handler(Looper.getMainLooper())
    private var currentStatus: String? = null
    private val installQueue = ArrayDeque<AppEntry>()
    private var installing = false
    private var current: AppEntry? = null
    private val statusViews = HashMap<String, TextView>()
    private val buttonViews = HashMap<String, Button>()
    private val uninstallViews = HashMap<String, Button>()
    private val entriesByPkg = HashMap<String, AppEntry>()
    private val pendingManual = HashMap<String, String>()   // instruções de passo manual (pasta data/obb) a mostrar após instalar
    private var permWaiting: AppEntry? = null
    private val lockedInstaller = HashSet<String>()   // instalador do Android travado (Lock/original) neste carro
    private val ACTION_UNINSTALL = "com.euler.catalog.UNINSTALL_STATUS"
    private lateinit var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private var selfLatestVc = 0
    private var selfUrl = ""
    private var currentPlan = "basico"
    private var currentGrupo = "byd_simples"
    private var selectedModel: String? = null
    private var continueBtn: Button? = null
    private val modelCards = ArrayList<Pair<LinearLayout, String>>()
    private data class Model(val name: String, val avail: Boolean, val grupo: String, val ask: Boolean)
    private val MODELS = listOf(
        Model("Dolphin Mini", true, "byd_simples", false),
        Model("Dolphin GS", true, "byd_simples", false),
        Model("Dolphin SE", true, "byd_google", false),
        Model("Dolphin Plus", true, "byd_simples", false),
        Model("King", true, "byd_simples", false),
        Model("Yuan Pro", true, "byd_simples", true),
        Model("Yuan Plus", true, "byd_simples", true),
        Model("Song Pro", true, "byd_simples", true),
        Model("Song Plus", true, "byd_simples", true),
        Model("Song Premium", true, "byd_simples", true),
        Model("Atto 2", true, "byd_google", false),
        Model("Atto 8", true, "byd_google", false),
        Model("Shark", true, "byd_simples", false),
        Model("Seal", true, "byd_simples", false),
        Model("Sealion", false, "byd_simples", false),
        Model("Geely EX2 Pro", true, "geely", false),
        Model("Geely EX2 Max", true, "geely", false),
        Model("Tiggo 7", true, "chery", false),
        Model("Tiggo 8", true, "chery", false),
        Model("Omoda 5", true, "chery", false),
        Model("Omoda E5", true, "chery", false),
        Model("Jaecoo 7", true, "chery", false)
    )

    // paleta
    private val BG = 0xFF0A0C10.toInt(); private val SURFACE = 0xFF141922.toInt()
    private val SURFACE2 = 0xFF1A2029.toInt(); private val BORDER = 0xFF232B38.toInt()
    private val TEXT = 0xFFE8ECF3.toInt(); private val MUTED = 0xFF8793A6.toInt()
    private val ACCENT = 0xFF2BB0A6.toInt(); private val GREEN = 0xFF27C08A.toInt(); private val RED = 0xFFEF5350.toInt()

    private data class AppEntry(val name: String, val pkg: String, val url: String, val icon: String, val desc: String, val plano: String = "basico", val grupos: List<String> = emptyList(), val build: Int = 0,
                                val extras: List<Extras.Extra> = emptyList(), val tutorial: String = "", val lockUrl: String = "", val size: Long = 0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, CatalogAdminReceiver::class.java)

        val scroll = ScrollView(this); scroll.setBackgroundColor(BG)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(40)) }
        scroll.addView(container)
        swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this)
        swipe.setColorSchemeColors(ACCENT); swipe.setProgressBackgroundColorSchemeColor(SURFACE)
        swipe.addView(scroll)
        swipe.setOnRefreshListener {
            if (!Store.isOnboarded(this)) { swipe.isRefreshing = false; return@setOnRefreshListener }
            Thread {
                val r = Store.register(this)
                runOnUiThread { currentPlan = r.plan; currentGrupo = r.grupo; swipe.isRefreshing = false; applyStatus(r.status, r.wipe, true) }
            }.start()
        }
        setContentView(swipe)

        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL))
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(uninstallReceiver, IntentFilter(ACTION_UNINSTALL), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(uninstallReceiver, IntentFilter(ACTION_UNINSTALL))

        cleanTemps()
        scheduleDailyCheck()
        if (Store.isOnboarded(this)) { checkGate(); startPolling() } else showOnboarding()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            Thread {
                val r = Store.register(this@MainActivity)
                runOnUiThread { currentPlan = r.plan; currentGrupo = r.grupo; applyStatus(r.status, r.wipe, false) }
            }.start()
            poller.postDelayed(this, 30_000)
        }
    }
    private fun startPolling() { poller.removeCallbacks(pollRunnable); poller.postDelayed(pollRunnable, 30_000) }
    private fun stopPolling() { poller.removeCallbacks(pollRunnable) }

    override fun onResume() { super.onResume(); if (Store.isOnboarded(this)) startPolling(); refreshInstalledStates() }
    override fun onPause() { super.onPause(); stopPolling() }

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
            val req = PeriodicWorkRequest.Builder(CheckWorker::class.java, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork("ubyd-check", ExistingPeriodicWorkPolicy.UPDATE, req)
        } catch (e: Exception) {}
    }

    private fun checkGate(firstTime: Boolean = false) {
        container.removeAllViews()
        container.addView(headerView("Verificando ativação…"))
        Thread {
            val r = Store.register(this, firstTime)
            runOnUiThread { currentPlan = r.plan; currentGrupo = r.grupo; applyStatus(r.status, r.wipe, true) }
        }.start()
    }

    /** Aplica o status vindo do servidor. force=true sempre renderiza; senão só quando muda. */
    private fun applyStatus(status: String, wipe: Boolean, force: Boolean) {
        if (status == "error") {
            if (force) { currentStatus = null; showLocked("Sem conexão", "Não foi possível verificar a ativação. Confira a internet do carro e tente de novo.") }
            return
        }
        if (!force && status == currentStatus) return
        currentStatus = status
        when (status) {
            "active" -> showCatalog()
            "revoked" -> { Thread { Store.applyRevoke(applicationContext, wipe) }.start()
                showLocked("Acesso revogado", "Este catálogo foi desativado para este veículo. Fale com a Unlock BYD para reativar.") }
            "pending" -> showLocked("Aguardando ativação", "Envie o código abaixo para a Unlock BYD liberar este veículo.")
            "deleted" -> { Store.clearOnboarding(this); stopPolling(); currentStatus = null; showOnboarding() }
        }
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
        for (m in MODELS) row.addView(modelCard(m.name, m.avail))
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
            val m = MODELS.firstOrNull { it.name == model }
            Store.saveOnboarding(this, model, nm, m?.grupo ?: "byd_simples", m?.ask ?: false)
            checkGate(true); startPolling()
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
            val sv = Store.selfVersion()
            runOnUiThread { selfLatestVc = sv.first; selfUrl = sv.second; renderCatalog(entries) }
        }.start()
    }

    private fun renderCatalog(entries: List<AppEntry>?) {
        container.removeAllViews(); statusViews.clear(); buttonViews.clear(); uninstallViews.clear(); entriesByPkg.clear()
        container.addView(headerView("Loja do veículo"))
        container.addView(selfCard())
        if (entries == null) {
            container.addView(tv("Não consegui carregar a lista. Verifique a internet.", 14f, MUTED).apply { setPadding(0, dp(10), 0, dp(12)) })
            val b = Button(this); b.text = "Tentar de novo"; styleBtn(b, ACCENT, 0xFF04201D.toInt()); b.setOnClickListener { showCatalog() }; container.addView(b); return
        }
        if (entries.isEmpty()) { container.addView(tv("Catálogo vazio.", 14f, MUTED)); return }

        val visible = entries.filter { (it.grupos.isEmpty() || it.grupos.contains(currentGrupo)) && planRank(it.plano) <= planRank(currentPlan) }
        container.addView(tv("${grupoLabel(currentGrupo)} · Plano ${planLabel(currentPlan)} · ${visible.size} ${if (visible.size == 1) "app" else "apps"}", 12f, ACCENT, true).apply { setPadding(0, dp(2), 0, dp(10)) })
        if (visible.isEmpty()) container.addView(tv("Nenhum app liberado para este plano ainda.", 14f, MUTED).apply { setPadding(0, dp(6), 0, dp(6)) })

        val all = Button(this); all.text = "Instalar todos os que faltam"; styleBtn(all, SURFACE2, TEXT)
        all.background = rounded(SURFACE2, 10, 1, BORDER); all.setTextColor(TEXT)
        val alp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); alp.topMargin = dp(4); all.layoutParams = alp
        all.setOnClickListener { enqueueAll(visible) }
        container.addView(all)

        for (e in visible) container.addView(appCard(e))

        if (dpm.isDeviceOwnerApp(packageName)) {
            val rm = Button(this); rm.text = "Aplicativo bloqueado para desinstalação"; styleBtn(rm, SURFACE2, MUTED)
            rm.background = rounded(SURFACE2, 9, 1, BORDER); rm.setTextColor(MUTED)
            val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); rlp.topMargin = dp(30); rm.layoutParams = rlp
            rm.setOnClickListener { requestRemoval() }
            container.addView(rm)
        }
    }

    private fun requestRemoval() {
        Thread {
            val allowed = Store.requestRemoval(this)
            runOnUiThread {
                if (allowed) {
                    try { dpm.clearDeviceOwnerApp(packageName); toast("Bloqueio removido. O app já pode ser desinstalado."); checkGate() }
                    catch (e: Exception) { toast("Não foi possível remover agora: ${e.message}") }
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Aplicativo bloqueado")
                        .setMessage("Este aplicativo não pode ser desinstalado da multimídia. Para desinstalá-lo, solicite à Unlock BYD a retirada do bloqueio.")
                        .setPositiveButton("Entendi", null)
                        .show()
                }
            }
        }.start()
    }

    private fun appCard(e: AppEntry): LinearLayout {
        // Cartão externo (vertical): linha do app + tutorial que abre ao tocar no nome.
        val outer = LinearLayout(this); outer.orientation = LinearLayout.VERTICAL
        outer.background = rounded(SURFACE, 14, 1, BORDER); outer.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(10); outer.layoutParams = lp
        val card = LinearLayout(this); card.orientation = LinearLayout.HORIZONTAL; card.gravity = Gravity.CENTER_VERTICAL

        val icon = ImageView(this); icon.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        icon.background = rounded(SURFACE2, 12, 1, BORDER); icon.setPadding(dp(8), dp(8), dp(8), dp(8)); setIcon(icon, e)

        val info = LinearLayout(this); info.orientation = LinearLayout.VERTICAL
        val ilp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); ilp.leftMargin = dp(12); ilp.rightMargin = dp(10); info.layoutParams = ilp
        info.addView(tv(e.name, 14.5f, TEXT, true))
        if (e.desc.isNotEmpty()) info.addView(tv(e.desc, 12f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        var tutView: TextView? = null
        if (e.tutorial.isNotEmpty()) {
            val hint = tv("Toque no nome para ver como instalar ▾", 11.5f, ACCENT, true).apply { setPadding(0, dp(4), 0, 0) }
            info.addView(hint)
            val t = tv(e.tutorial, 13f, TEXT).apply {
                setLineSpacing(dp(3).toFloat(), 1f); visibility = View.GONE
                background = rounded(SURFACE2, 10, 1, BORDER); setPadding(dp(12), dp(10), dp(12), dp(10))
                val tlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); tlp.topMargin = dp(10); layoutParams = tlp
            }
            tutView = t
            info.setOnClickListener {
                val open = t.visibility != View.VISIBLE
                t.visibility = if (open) View.VISIBLE else View.GONE
                hint.text = if (open) "Ocultar instruções ▴" else "Toque no nome para ver como instalar ▾"
            }
        }
        val inst = isInstalled(e.pkg)
        val st = tv(stateText(e, inst), 11f, if (inst) GREEN else MUTED).apply { setPadding(0, dp(4), 0, 0) }
        statusViews[e.pkg] = st
        info.addView(st)

        val btn = Button(this); btn.text = if (inst) "Reinstalar" else "Instalar"
        styleBtn(btn, if (inst) SURFACE2 else ACCENT, if (inst) TEXT else 0xFF04201D.toInt())
        if (inst) btn.background = rounded(SURFACE2, 9, 1, BORDER)
        btn.setOnClickListener { enqueue(e) }
        buttonViews[e.pkg] = btn
        entriesByPkg[e.pkg] = e

        // Coluna de botões: Instalar/Reinstalar + Desinstalar (só aparece se o app estiver instalado).
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER_HORIZONTAL
        val un = Button(this); un.text = "Desinstalar"; styleBtn(un, SURFACE, RED); un.background = rounded(SURFACE, 9, 1, RED)
        val ulp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); ulp.topMargin = dp(6); un.layoutParams = ulp
        un.visibility = if (inst && e.lockUrl.isEmpty()) View.VISIBLE else View.GONE
        un.setOnClickListener { confirmUninstall(e) }
        uninstallViews[e.pkg] = un
        btn.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        col.addView(btn); col.addView(un)

        card.addView(icon); card.addView(info); card.addView(col)
        if (e.lockUrl.isNotEmpty()) checkInstallerState(e)
        outer.addView(card); tutView?.let { outer.addView(it) }
        return outer
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

    private fun planRank(p: String): Int = when (p.lowercase()) { "premium" -> 3; "intermediario", "intermediário" -> 2; else -> 1 }
    private fun planLabel(p: String): String = when (p.lowercase()) { "premium" -> "Premium"; "intermediario", "intermediário" -> "Intermediário"; else -> "Básico" }
    private fun grupoLabel(g: String): String = when (g.lowercase()) { "byd_google" -> "BYD · Google"; "chery" -> "Chery"; "geely" -> "Geely"; else -> "BYD · Simples" }

    private fun parseCatalog(json: String): List<AppEntry> {
        val arr = JSONObject(json).optJSONArray("apps") ?: return emptyList()
        val out = ArrayList<AppEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val n = o.optString("name").trim(); val p = o.optString("package").trim(); val u = o.optString("url").trim()
            if (n.isNotEmpty() && p.isNotEmpty() && u.isNotEmpty()) {
                val g = ArrayList<String>()
                o.optJSONArray("grupos")?.let { for (k in 0 until it.length()) { val v = it.optString(k).trim().lowercase(); if (v.isNotEmpty()) g.add(v) } }
                out.add(AppEntry(n, p, u, o.optString("icon").trim(), o.optString("desc").trim(), o.optString("plano", "basico").trim().ifEmpty { "basico" }, g, o.optInt("build", 0),
                    Extras.parse(o), o.optString("tutorial").trim(), o.optString("lock_url").trim(), o.optLong("tamanho", 0L)))
            }
        }
        return out
    }

    // ---------- auto-atualização do próprio app ----------
    private fun ownVersionCode(): Int = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    private fun selfCard(): LinearLayout {
        val updatable = selfLatestVc > ownVersionCode() && selfUrl.isNotEmpty()
        val card = LinearLayout(this); card.orientation = LinearLayout.HORIZONTAL; card.gravity = Gravity.CENTER_VERTICAL
        card.background = rounded(SURFACE, 14, if (updatable) 2 else 1, if (updatable) ACCENT else BORDER); card.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(4); lp.bottomMargin = dp(6); card.layoutParams = lp
        val icon = ImageView(this); icon.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        icon.background = rounded(0xFFFFFFFF.toInt(), 12); icon.setPadding(dp(6), dp(6), dp(6), dp(6)); icon.setImageResource(R.mipmap.ic_launcher)
        val info = LinearLayout(this); info.orientation = LinearLayout.VERTICAL
        val ilp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); ilp.leftMargin = dp(12); ilp.rightMargin = dp(10); info.layoutParams = ilp
        info.addView(tv("Unlock BYD", 14.5f, TEXT, true))
        info.addView(tv("Aplicativo da loja", 12f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        val st = tv(if (updatable) "Atualização disponível" else "Instalado · atualizado", 11f, if (updatable) ACCENT else GREEN).apply { setPadding(0, dp(4), 0, 0) }
        statusViews[packageName] = st; info.addView(st)
        card.addView(icon); card.addView(info)
        if (updatable) {
            val btn = Button(this); btn.text = "Atualizar"; styleBtn(btn, ACCENT, 0xFF04201D.toInt())
            btn.setOnClickListener { enqueue(AppEntry("Unlock BYD", packageName, selfUrl, "", "")) }
            card.addView(btn)
        }
        return card
    }

    // ---------- fila de download/instalação (um por vez) ----------
    private fun enqueueAll(entries: List<AppEntry>) { entries.filter { !isInstalled(it.pkg) || lockedInstaller.contains(it.pkg) }.forEach { enqueue(it) } }

    /** Instalador do Android: mostra "Liberado" (Unlock instalado) ou "Travado" (instalador da BYD). */
    private fun checkInstallerState(e: AppEntry) {
        setStatus(e.pkg, "Verificando…", MUTED)
        Thread {
            val unlocked = Store.installerUnlocked(applicationContext, Store.InstallerSwap(e.pkg, e.url, e.lockUrl))
            runOnUiThread {
                when (unlocked) {
                    true -> { lockedInstaller.remove(e.pkg); setStatus(e.pkg, "Liberado", GREEN) }
                    false -> { lockedInstaller.add(e.pkg); setStatus(e.pkg, "Travado · toque em Instalar para liberar", MUTED)
                        buttonViews[e.pkg]?.let { b -> b.text = "Instalar"; styleBtn(b, ACCENT, 0xFF04201D.toInt()) } }
                    null -> setStatus(e.pkg, "Instalado", GREEN)
                }
            }
        }.start()
    }

    private fun enqueue(e: AppEntry) {
        if (current?.pkg == e.pkg || installQueue.any { it.pkg == e.pkg }) return
        if (Extras.needsStoragePermission(e.extras) && !hasStorage()) {
            // Android 10: precisa de acesso ao armazenamento para gravar em Android/obb e Android/data.
            try { dpm.setPermissionGrantState(admin, packageName, android.Manifest.permission.WRITE_EXTERNAL_STORAGE, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) } catch (_: Exception) {}
            if (!hasStorage()) { permWaiting = e; requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE), 77); return }
        }
        installQueue.addLast(e); setStatus(e.pkg, "Na fila…", MUTED)
        if (!installing) processNext()
    }

    private fun setStatus(pkg: String, text: String, color: Int) {
        statusViews[pkg]?.let { it.text = text; it.setTextColor(color) }
    }

    /** Marca como instalado em tempo real: status verde + botão vira "Reinstalar". */
    /** "Disponível · ~1,3 GB" / "Instalado · ~1,3 GB" (tamanho aproximado: APK + extras). */
    private fun stateText(e: AppEntry?, inst: Boolean): String {
        val base = if (inst) "Instalado" else "Disponível"
        return if (e != null && e.size > 0) "$base · ~${Extras.sizeText(e.size)}" else base
    }

    private fun markInstalled(pkg: String) {
        setStatus(pkg, if (entriesByPkg[pkg]?.lockUrl.isNullOrEmpty()) stateText(entriesByPkg[pkg], true) else "Liberado", GREEN)
        buttonViews[pkg]?.let { b -> b.text = "Reinstalar"; styleBtn(b, SURFACE2, TEXT); b.background = rounded(SURFACE2, 9, 1, BORDER) }
        if (entriesByPkg[pkg]?.lockUrl.isNullOrEmpty()) uninstallViews[pkg]?.visibility = View.VISIBLE else lockedInstaller.remove(pkg)
    }

    private fun processNext() {
        val e = installQueue.removeFirstOrNull()
        if (e == null) { installing = false; current = null; return }
        installing = true; current = e
        setStatus(e.pkg, "Baixando…  0%", MUTED)
        Thread {
            // 1) Arquivos extras (ex.: discos) — vão para Download/<pasta>/ antes do APK.
            val steps = e.extras.size + 1
            val manuals = ArrayList<String>()
            for ((idx, ex) in e.extras.withIndex()) {
                val tag = "(${idx + 1}/$steps)"
                runOnUiThread { setStatus(e.pkg, "$tag Baixando ${ex.nome}…", MUTED) }
                val res = try { Extras.fetch(applicationContext, ex, e.pkg) { txt -> runOnUiThread { setStatus(e.pkg, "$tag $txt", MUTED) } } }
                          catch (x: Exception) { Extras.Result(false) }
                if (!res.ok) { val why = res.erro?.let { ": $it" } ?: ""
                    runOnUiThread { setStatus(e.pkg, "Falha em ${ex.nome}$why", RED); current = null; processNext() }; return@Thread }
                res.manual?.let { if (!manuals.contains(it)) manuals.add(it) }
            }
            if (manuals.isNotEmpty()) pendingManual[e.pkg] = manuals.joinToString("\n\n— — —\n\n")
            // 2) APK
            val pre = if (steps > 1) "($steps/$steps) " else ""
            val dl = File(cacheDir, e.pkg + ".dl")
            val ok = try { download(e.url, dl) { pct, bytes -> runOnUiThread {
                setStatus(e.pkg, if (pct >= 0) "Baixando ${pre}APK…  $pct%" else "Baixando ${pre}APK…  ${bytes / 1_000_000} MB", MUTED)
            } } } catch (ex: Exception) { false }
            if (!ok || dl.length() == 0L) { runOnUiThread { setStatus(e.pkg, "Falha no download", RED); current = null; processNext() }; return@Thread }
            val splits = try { extractSplits(dl, e.pkg) } catch (ex: Exception) { null }
            if (splits != null) try { dl.delete() } catch (_: Exception) {}  // .dl já extraído, libera espaço
            runOnUiThread { setStatus(e.pkg, "Instalando…", ACCENT) }
            // A cópia dos bytes para a sessão roda AQUI, na thread de fundo — não trava a tela.
            try {
                when {
                    splits == null -> installSession(listOf(dl))
                    splits.isEmpty() -> { runOnUiThread { setStatus(e.pkg, "Pacote inválido", RED); cleanTemps(); current = null; processNext() }; return@Thread }
                    else -> installSession(splits)
                }
            } catch (ex: Exception) {
                runOnUiThread { setStatus(e.pkg, "Falha: ${ex.message}", RED); cleanTemps(); current = null; processNext() }
            }
        }.start()
    }

    private fun download(urlStr: String, dest: File, onProgress: (Int, Long) -> Unit): Boolean {
        var url = urlStr; var redirects = 0
        while (redirects < 6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 300000 }
            val code = c.responseCode
            if (code in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); if (loc.isNullOrBlank()) return false; url = loc; redirects++; continue }
            if (code != 200) { c.disconnect(); return false }
            try {
                val total = c.contentLengthLong
                c.inputStream.use { inp ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var readTotal = 0L; var lastPct = -1; var lastMark = 0L
                        while (true) {
                            val r = inp.read(buf); if (r < 0) break
                            out.write(buf, 0, r); readTotal += r
                            if (total > 0) { val pct = ((readTotal * 100) / total).toInt(); if (pct != lastPct) { lastPct = pct; onProgress(pct, readTotal) } }
                            else if (readTotal - lastMark >= 1_000_000) { lastMark = readTotal; onProgress(-1, readTotal) }
                        }
                        out.flush()
                    }
                }
                return true
            } catch (e: Exception) { return false } finally { c.disconnect() }
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
            val entries = zf.entries().toList().filter { !it.isDirectory }
            // Já é um APK normal (AndroidManifest.xml na raiz)? Instala direto — .apk dentro de assets/ são recursos, não splits.
            if (entries.any { it.name == "AndroidManifest.xml" }) return null
            // Pacote (xapk/apks): considera só os .apk do topo do zip; nunca os de dentro de assets/.
            var apkEntries = entries.filter { it.name.endsWith(".apk", true) && !it.name.contains("/") }
            if (apkEntries.isEmpty())
                apkEntries = entries.filter { it.name.endsWith(".apk", true) && !it.name.contains("assets/") && !it.name.contains("standalones/") }
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
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val total = apks.sumOf { it.length() }
        if (total > 0) try { params.setSize(total) } catch (_: Exception) {}
        val sessionId = pi.createSession(params)
        val session = pi.openSession(sessionId); var i = 0
        for (f in apks) { session.openWrite("split_$i.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; session.fsync(out) }; i++ }
        // Os bytes já estão na sessão: apaga os temporários agora pra liberar espaço antes da cópia final.
        for (f in apks) { try { f.delete(); f.parentFile?.let { if (it.name.startsWith("sp_")) it.delete() } } catch (_: Exception) {} }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(this, sessionId, Intent(ACTION_INSTALL).setPackage(packageName), flags)
        session.commit(pending.intentSender); session.close()
    }

    /** Apaga downloads (.dl) e extrações (sp_*) acumulados na pasta de cache. */
    private fun cleanTemps() {
        try { cacheDir.listFiles()?.forEach { if (it.name.endsWith(".dl") || it.name.startsWith("sp_")) it.deleteRecursively() } } catch (_: Exception) {}
    }

    private fun hasStorage(): Boolean = Build.VERSION.SDK_INT > 29 ||
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 77) return
        val e = permWaiting ?: return; permWaiting = null
        // Mesmo se negar, segue: o que não puder ir direto vai para a pasta Download com instruções.
        installQueue.addLast(e); setStatus(e.pkg, "Na fila…", MUTED)
        if (!installing) processNext()
    }

    /** Depois de instalar: mostra como terminar à mão (pasta data/obb que o Android não deixou colocar sozinho). */
    private fun showManual(e: AppEntry) {
        val msg = pendingManual.remove(e.pkg) ?: return
        setStatus(e.pkg, "Instalado · falta mover a pasta (veja o aviso)", ACCENT)
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("${e.name}: falta um passo")
                .setMessage(msg)
                .setPositiveButton("Entendi", null)
                .show()
        } catch (_: Exception) {}
    }

    // ---------- desinstalação ----------
    private fun confirmUninstall(e: AppEntry) {
        if (current?.pkg == e.pkg || installQueue.any { it.pkg == e.pkg }) { toast("Aguarde terminar a instalação"); return }
        val extra = if (e.extras.isNotEmpty()) "\n\nOs arquivos extras deste app (${e.extras.joinToString { it.nome }}) também serão apagados da pasta Download." else ""
        android.app.AlertDialog.Builder(this)
            .setTitle("Desinstalar ${e.name}?")
            .setMessage("O app será removido do carro.$extra")
            .setPositiveButton("Desinstalar") { _, _ -> uninstall(e) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun uninstall(e: AppEntry) {
        setStatus(e.pkg, "Desinstalando…", MUTED)
        if (e.extras.isNotEmpty()) Thread { for (x in e.extras) try { Extras.remove(applicationContext, x) } catch (_: Exception) {} }.start()
        if (dpm.isDeviceOwnerApp(packageName)) {
            // Device Owner: remove em silêncio.
            try {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(this, e.pkg.hashCode(), Intent(ACTION_UNINSTALL).setPackage(packageName).putExtra("pkg", e.pkg), flags)
                packageManager.packageInstaller.uninstall(e.pkg, pi.intentSender)
            } catch (ex: Exception) { setStatus(e.pkg, "Falha ao desinstalar", RED) }
        } else {
            // Sem Device Owner: abre a confirmação do Android.
            try { startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + e.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (ex: Exception) { setStatus(e.pkg, "Falha ao desinstalar", RED) }
        }
    }

    /** Marca como não instalado em tempo real. */
    private fun markUninstalled(pkg: String) {
        setStatus(pkg, stateText(entriesByPkg[pkg], false), MUTED)
        buttonViews[pkg]?.let { b -> b.text = "Instalar"; styleBtn(b, ACCENT, 0xFF04201D.toInt()) }
        uninstallViews[pkg]?.visibility = View.GONE
        try { Store.setBuild(applicationContext, pkg, 0) } catch (_: Exception) {}
    }

    private val uninstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val pkg = i?.getStringExtra("pkg") ?: return
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_SUCCESS -> markUninstalled(pkg)
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                else -> setStatus(pkg, "Falha ao desinstalar", RED)
            }
        }
    }

    // Ao voltar da tela de confirmação do Android (sem Device Owner), atualiza o que foi removido.
    private fun refreshInstalledStates() {
        for ((pkg, _) in entriesByPkg) if (!isInstalled(pkg) && uninstallViews[pkg]?.visibility == View.VISIBLE) markUninstalled(pkg)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_SUCCESS -> {
                    val pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                    if (pkg != null) Thread { grantAll(pkg) }.start()
                    val cur = current
                    if (cur != null) { markInstalled(cur.pkg); Store.setBuild(applicationContext, cur.pkg, cur.build); showManual(cur); current = null; processNext() }
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    val cur = current; if (cur != null) { setStatus(cur.pkg, "Confirme na tela…", MUTED); current = null; processNext() }
                }
                else -> {
                    val msg = i?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    val cur = current
                    if (cur != null) {
                        val low = (msg ?: "").lowercase()
                        val txt = when {
                            low.contains("storage") || low.contains("insufficient") -> "Falha: sem espaço no carro"
                            msg.isNullOrEmpty() -> "Falha na instalação"
                            else -> "Falha: ${msg.take(50)}"
                        }
                        setStatus(cur.pkg, txt, RED); cleanTemps(); current = null; processNext()
                    } else if (!msg.isNullOrEmpty()) toast("Erro: $msg")
                }
            }
        }
    }

    private fun grantAll(pkg: String) {
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val perms = try { packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions } catch (e: Exception) { null } ?: return
        for (p in perms) try { dpm.setPermissionGrantState(admin, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) } catch (e: Exception) {}
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { super.onDestroy(); stopPolling(); try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}; try { unregisterReceiver(uninstallReceiver) } catch (e: Exception) {} }
}
