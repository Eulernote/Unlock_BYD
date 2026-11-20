package com.euler.catalog

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Re-habilita o início automático do Unlock BYD DEPOIS de cada atualização, do jeito que o
 * Electro faz: abre a tela de início automático da BYD, acha a LINHA do nosso app, liga o
 * botão daquela linha e fecha — tudo sozinho.
 *
 * Por que um AccessibilityService: o BYD zera o início automático a cada instalação e, com ele
 * desligado, BLOQUEIA o app de rodar em segundo plano (o BootReceiver/CheckWorker nem dispara).
 * Um serviço de acessibilidade é gerenciado pelo sistema: roda sem início automático e é
 * RE-VINCULADO automaticamente depois que o app atualiza — então acorda na hora certa
 * (pós-update) e religa sozinho. Neste firmware os caminhos de shell (appops/content provider)
 * nem existem, então a UI é o único jeito que funciona.
 *
 * Ligado em silêncio via ADB/Device Owner (AdbShell.enableAccessibilityService).
 */
class AutostartService : AccessibilityService() {

    private val ui = Handler(Looper.getMainLooper())
    private var attempts = 0

    override fun onServiceConnected() {
        instance = this
        // Auto-cura: toda vez que (re)conecta — inclusive logo após uma atualização — religa
        // o início automático sozinho, sem precisar abrir o app nem apertar botão.
        Thread {
            try {
                AdbShell.enableAutostart(applicationContext)     // reforço (inócuo se não existir)
                BydAutostart.trySet(applicationContext, true)    // API de hardware (best-effort)
                if (shouldRunAuto(applicationContext)) requestEnable(applicationContext)
            } catch (_: Exception) {}
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (AUTOSTART_PKGS.none { pkg.startsWith(it) }) return
        try { tryToggle() } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Procura a linha do Unlock BYD que esteja VISÍVEL na tela, pega o botão (switch) da MESMA
     * linha (pela posição), e liga se estiver desligado. Se a linha não estiver visível, rola.
     */
    private fun tryToggle() {
        val root = rootInActiveWindow ?: return
        val dump = StringBuilder()
        val me = packageName
        val label = getString(R.string.app_name)

        // 1) Nó de texto do nosso app que esteja desenhado na tela (bounds válidos).
        val textNode = findOnScreenLabel(root, label, me, dump)
        if (textNode == null) {
            // Não está visível: rola pra baixo e espera o próximo evento.
            attempts++
            if (attempts <= MAX_SCROLLS) {
                findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } else {
                lastResult = "não achei a linha do app visível após rolar ${MAX_SCROLLS}x"
                lastDump = dump.toString().take(6000)
                finish(false)
            }
            return
        }

        val textRect = Rect().also { textNode.getBoundsInScreen(it) }

        // 2) Switch/checkable da MESMA linha (centro vertical próximo ao do texto).
        val sw = findSwitchOnRow(root, textRect, dump)
        if (sw == null) {
            lastResult = "achei a linha mas não o botão da linha (ver textos lidos)"
            lastDump = dump.toString().take(6000)
            // tenta rolar um pouco, talvez o switch esteja cortado
            attempts++
            if (attempts <= MAX_SCROLLS) findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else finish(false)
            return
        }

        // ATENÇÃO: esta tela é "Desativar inicialização automática" — o switch é INVERTIDO.
        // Switch LIGADO = início automático DESLIGADO. Para HABILITAR, precisamos DESLIGAR o switch.
        val switchOn = sw.isChecked      // true = início automático está desabilitado
        if (switchOn) {
            val target = if (sw.isClickable) sw else clickableAncestor(sw) ?: sw
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            lastResult = "início automático estava DESLIGADO (switch ligado na tela 'desativar'); " +
                    "desliguei o switch pra habilitar (clique ${if (clicked) "ok" else "falhou"})"
            finish(clicked)
        } else {
            lastResult = "início automático já habilitado (switch da linha desligado)"
            finish(true)
        }
    }

    private fun finish(success: Boolean) {
        attempts = 0
        armed = false
        if (success) markAutoDone(applicationContext)
        ui.postDelayed({ try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {} }, 700)
    }

    // ---- busca na árvore ----

    /** Nó de texto (na tela) cujo texto contém o nome ou o pacote do app. */
    private fun findOnScreenLabel(root: AccessibilityNodeInfo, label: String, pkg: String, dump: StringBuilder): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        val screen = screenRect()
        bfs(root) { n ->
            val t = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim()
            if (t.isNotBlank() && dump.length < 6000) dump.append(t).append(" | ")
            if (best == null && (t.contains(label, true) || t.contains(pkg, true))) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0 && Rect.intersects(r, screen)) best = n
            }
        }
        return best
    }

    /** Switch/checkable cujo centro vertical bate com a linha do texto (mesma fileira). */
    private fun findSwitchOnRow(root: AccessibilityNodeInfo, textRect: Rect, dump: StringBuilder): AccessibilityNodeInfo? {
        val rowCy = textRect.centerY()
        val tol = maxOf(textRect.height(), 110)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE
        bfs(root) { n ->
            val cn = n.className?.toString() ?: ""
            val checkable = n.isCheckable || cn.contains("Switch", true) || cn.contains("CheckBox", true) ||
                            cn.contains("Toggle", true) || cn.contains("Checkable", true)
            if (checkable) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) {
                    val dy = Math.abs(r.centerY() - rowCy)
                    if (dy <= tol) {
                        // mesma linha: prefere o mais próximo verticalmente; desempate pelo mais à direita.
                        val score = dy * 10000 - r.centerX()
                        if (score < bestScore) { bestScore = score; best = n }
                        if (dump.length < 6000) dump.append("[sw checked=${n.isChecked} y=${r.centerY()}] ")
                    }
                }
            }
        }
        return best
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var up = 0
        while (cur != null && up < 5) {
            if (cur.isClickable) return cur
            cur = cur.parent; up++
        }
        return null
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        bfs(root) { n -> if (found == null && n.isScrollable) found = n }
        return found
    }

    private fun screenRect(): Rect {
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun bfs(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var guard = 0
        while (q.isNotEmpty() && guard < 5000) {
            val n = q.removeFirst(); guard++
            try { visit(n) } catch (_: Exception) {}
            for (i in 0 until n.childCount) { n.getChild(i)?.let { q.add(it) } }
        }
    }

    companion object {
        @Volatile var instance: AutostartService? = null
        @Volatile var armed = false
        @Volatile var lastResult = "(ainda não rodou)"
        @Volatile var lastDump = ""

        private const val MAX_SCROLLS = 12
        private const val MAX_AUTO_TRIES = 8
        val AUTOSTART_PKGS = listOf("com.byd.appstartmanagement", "com.byd.setting")

        private val AUTOSTART_ACTIVITIES = listOf(
            "com.byd.appstartmanagement" to "com.byd.appstartmanagement.frame.AppStartManagement",
            "com.byd.appstartmanagement" to "com.byd.appstartmanagement.AppStartManagement",
            "com.byd.setting" to "com.byd.setting.activity.AutoStartActivity"
        )

        fun isEnabled(ctx: Context): Boolean {
            return try {
                val flat = Settings.Secure.getString(ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
                val me = ComponentName(ctx, AutostartService::class.java)
                flat.split(':').any {
                    it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
                }
            } catch (e: Exception) { false }
        }

        /** Abre a tela de início automático da BYD e arma o serviço pra ligar o botão da nossa linha. */
        fun requestEnable(ctx: Context) {
            armed = true
            instance?.attempts = 0
            if (!openAutostartUi(ctx)) Thread { AdbShell.openAutostartPage(ctx) }.start()
        }

        private fun openAutostartUi(ctx: Context): Boolean {
            for ((pkg, cls) in AUTOSTART_ACTIVITIES) {
                try {
                    val i = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i); return true
                } catch (_: Exception) {}
            }
            return false
        }

        /** Rodar o fluxo automático? Uma vez confirmado por versão; senão tenta até MAX_AUTO_TRIES. */
        private fun shouldRunAuto(ctx: Context): Boolean {
            return try {
                val p = ctx.getSharedPreferences("ubyd", Context.MODE_PRIVATE)
                val vc = ownVc(ctx)
                if (p.getInt("a11yDoneVc", -1) == vc) return false
                val tries = p.getInt("a11yTries_$vc", 0)
                if (tries >= MAX_AUTO_TRIES) return false
                p.edit().putInt("a11yTries_$vc", tries + 1).apply()
                true
            } catch (e: Exception) { false }
        }

        private fun markAutoDone(ctx: Context) {
            try {
                ctx.getSharedPreferences("ubyd", Context.MODE_PRIVATE)
                    .edit().putInt("a11yDoneVc", ownVc(ctx)).apply()
            } catch (_: Exception) {}
        }

        private fun ownVc(ctx: Context): Int = try {
            @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        } catch (e: Exception) { -1 }
    }
}
