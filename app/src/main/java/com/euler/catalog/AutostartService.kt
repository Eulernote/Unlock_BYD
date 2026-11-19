package com.euler.catalog

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Re-habilita o início automático do Unlock BYD DEPOIS de cada atualização, do jeito que o
 * Electro faz: abre a tela de início automático da BYD, acha a linha do nosso app, liga o
 * botão e fecha a tela — tudo sozinho.
 *
 * Por que um AccessibilityService: o BYD zera a whitelist de início automático a cada
 * instalação, e com ela desligada o sistema BLOQUEIA o app de rodar em segundo plano (o
 * BootReceiver/MY_PACKAGE_REPLACED nem dispara). Um serviço de acessibilidade é gerenciado
 * pelo sistema: roda sem depender do início automático e é RE-VINCULADO automaticamente
 * depois que o app atualiza. Então ele é a única peça que acorda na hora certa (pós-update)
 * para religar o início automático.
 *
 * O serviço é ligado em silêncio via ADB/Device Owner (AdbShell.enableAccessibilityService).
 */
class AutostartService : AccessibilityService() {

    private val ui = Handler(Looper.getMainLooper())
    private var attempts = 0

    override fun onServiceConnected() {
        instance = this
        // Auto-cura: toda vez que o serviço (re)conecta — inclusive logo após uma atualização —
        // reforça por shell/API e, uma vez por versão, roda o fluxo de UI pra ligar o botão.
        Thread {
            try {
                AdbShell.enableAutostart(applicationContext)           // reforço silencioso
                BydAutostart.trySet(applicationContext, true)          // API de hardware (best-effort)
                if (shouldRunUiOncePerVersion()) requestEnable(applicationContext)
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

    /** Procura a linha do Unlock BYD, liga o switch se estiver desligado, e fecha a tela. */
    private fun tryToggle() {
        val root = rootInActiveWindow ?: return
        val dump = StringBuilder()
        val me = packageName
        val label = getString(R.string.app_name)

        val row = findOurRow(root, label, me, dump)
        if (row != null) {
            val sw = findCheckable(row)
            if (sw != null) {
                val wasOn = sw.isChecked
                if (!wasOn) clickable(sw)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastResult = "linha encontrada; já ligado=$wasOn → ${if (wasOn) "nada a fazer" else "liguei"}"
                finish()
                return
            }
            lastResult = "linha encontrada mas sem botão de liga/desliga"
        }

        // Não achou: rola pra baixo e espera o próximo evento re-disparar.
        attempts++
        if (attempts <= MAX_SCROLLS) {
            findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } else {
            lastResult = "não encontrei a linha do app após rolar ${MAX_SCROLLS}x"
            lastDump = dump.toString().take(6000)
            finish()
        }
    }

    private fun finish() {
        attempts = 0
        armed = false
        ui.postDelayed({ try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {} }, 700)
    }

    // ---- busca na árvore de nós ----

    /** Nó-linha que contém o nome/pacote do app (sobe até um ancestral que tenha um checkable). */
    private fun findOurRow(root: AccessibilityNodeInfo, label: String, pkg: String, dump: StringBuilder): AccessibilityNodeInfo? {
        val matches = ArrayList<AccessibilityNodeInfo>()
        bfs(root) { n ->
            val t = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
            if (dump.length < 6000 && t.isNotBlank()) dump.append(t.trim()).append(" | ")
            if (t.contains(label, true) || t.contains(pkg, true)) matches.add(n)
        }
        for (m in matches) {
            var cur: AccessibilityNodeInfo? = m
            var up = 0
            while (cur != null && up < 5) {
                if (findCheckable(cur) != null) return cur
                cur = cur.parent; up++
            }
        }
        return matches.firstOrNull()
    }

    /** Primeiro descendente "checável" (Switch/Checkbox/ToggleButton) dentro do nó. */
    private fun findCheckable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        bfs(node) { n ->
            if (found == null) {
                val cn = n.className?.toString() ?: ""
                if (n.isCheckable || cn.contains("Switch", true) || cn.contains("CheckBox", true) ||
                    cn.contains("Toggle", true) || cn.contains("Checkable", true)) found = n
            }
        }
        return found
    }

    /** Sobe do checkable até um ancestral clicável (às vezes o clique é na linha, não no switch). */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var up = 0
        while (cur != null && up < 4) {
            if (cur.isClickable) return cur
            cur = cur.parent; up++
        }
        return node
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        bfs(root) { n -> if (found == null && n.isScrollable) found = n }
        return found
    }

    private fun bfs(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var guard = 0
        while (q.isNotEmpty() && guard < 4000) {
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

        private const val MAX_SCROLLS = 10
        val AUTOSTART_PKGS = listOf("com.byd.appstartmanagement", "com.byd.setting")

        /** Componentes conhecidos da tela de início automático da BYD. */
        private val AUTOSTART_ACTIVITIES = listOf(
            "com.byd.appstartmanagement" to "com.byd.appstartmanagement.frame.AppStartManagement",
            "com.byd.appstartmanagement" to "com.byd.appstartmanagement.AppStartManagement",
            "com.byd.setting" to "com.byd.setting.activity.AutoStartActivity"
        )

        /** O serviço está ligado nas configurações de acessibilidade? */
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

        /** Abre a tela de início automático da BYD e arma o serviço pra ligar o botão quando ela aparecer. */
        fun requestEnable(ctx: Context) {
            armed = true
            instance?.attempts = 0
            if (!openAutostartUi(ctx)) {
                // Sem conseguir abrir pelo Context, tenta pelo shell (am start).
                Thread { AdbShell.openAutostartPage(ctx) }.start()
            }
        }

        private fun openAutostartUi(ctx: Context): Boolean {
            for ((pkg, cls) in AUTOSTART_ACTIVITIES) {
                try {
                    val i = Intent().setComponent(ComponentName(pkg, cls))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    return true
                } catch (_: Exception) {}
            }
            return false
        }

        private fun shouldRunUiOncePerVersionStatic(ctx: Context): Boolean {
            return try {
                val p = ctx.getSharedPreferences("ubyd", Context.MODE_PRIVATE)
                val vc = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                    @Suppress("DEPRECATION") it.versionCode
                }
                if (p.getInt("a11yUiVc", -1) == vc) false
                else { p.edit().putInt("a11yUiVc", vc).apply(); true }
            } catch (e: Exception) { false }
        }
    }

    private fun shouldRunUiOncePerVersion(): Boolean = shouldRunUiOncePerVersionStatic(applicationContext)
}
