package com.euler.catalog

import android.content.Context
import android.content.ContextWrapper
import android.os.Binder
import android.os.Build

/**
 * Liberações internas da BYD (v77), copiadas do Overdrive (que funciona no Dolphin GS):
 *  1) accmodemanager.setPkg2AccWhiteList(pacote) → o app entra na lista branca do ACC (permite manter acordada).
 *  2) bg_datacache.setAppOpsData(uid, 0) (Android ≤ 11) / byd_datacached.setAppStartupData(uid, 0) (Android 12+)
 *     → libera o app no cache de permissões da BYD (leituras da bateria, ar, etc.).
 * Feitas com o contexto do sistema (ActivityThread.systemMain) e com as APIs escondidas liberadas.
 */
object BydUnlock {
    /** Contexto que responde "permitido" para checagens de permissão feitas do lado do app. */
    class BypassCtx(base: Context) : ContextWrapper(base) {
        private val G = android.content.pm.PackageManager.PERMISSION_GRANTED
        override fun checkPermission(permission: String, pid: Int, uid: Int) = G
        override fun checkCallingPermission(permission: String) = G
        override fun checkCallingOrSelfPermission(permission: String) = G
        override fun checkSelfPermission(permission: String) = G
        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}
        override fun enforceCallingPermission(permission: String, message: String?) {}
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
        override fun getApplicationContext(): Context = this
    }

    @Volatile var lastLog = ""
    @Volatile private var appLockToken: Binder? = null

    @Synchronized
    fun apply(ctx: Context): String {
        val out = StringBuilder()
        val pkg = ctx.packageName
        try {
            if (Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
            out.append("APIs escondidas: liberadas\n")
        } catch (t: Throwable) { out.append("APIs escondidas: ${t.javaClass.simpleName}\n") }
        val sys = systemPkgContext(ctx, out)
        for ((nome, c) in listOfNotNull(sys?.let { "sistema" to it }, "app" to ctx.applicationContext)) {
            val a = accWhitelist(c, pkg)
            val d = datacache(c, pkg)
            out.append("[$nome] ACC lista branca: $a\n[$nome] cache BYD: $d\n")
            if (a.startsWith("OK") && d.startsWith("OK")) break
        }
        lastLog = out.toString().trim()
        return lastLog
    }

    private fun err(t: Throwable): String { val c = t.cause ?: t; return "${c.javaClass.simpleName}: ${(c.message ?: "").take(140)}" }

    private fun systemPkgContext(ctx: Context, out: StringBuilder): Context? {
        var result: Context? = null
        val th = Thread {
            try {
                if (android.os.Looper.myLooper() == null) android.os.Looper.prepare()
                val at = Class.forName("android.app.ActivityThread")
                val main = at.getMethod("systemMain").invoke(null)
                val sys = at.getMethod("getSystemContext").invoke(main) as Context
                result = sys.createPackageContext(ctx.packageName, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            } catch (t: Throwable) { out.append("contexto do sistema: ${err(t)}\n") }
        }
        th.start(); th.join(10_000)
        if (th.isAlive) { th.interrupt(); out.append("contexto do sistema: demorou demais\n") }
        return result
    }

    private fun accWhitelist(c: Context, pkg: String): String { return try {
        val svc = BypassCtx(c).getSystemService("accmodemanager") ?: return "serviço não existe"
        val f = svc.javaClass.getDeclaredField("mService"); f.isAccessible = true
        val inner = f.get(svc) ?: return "mService nulo"
        val m = inner.javaClass.getDeclaredMethod("setPkg2AccWhiteList", String::class.java); m.isAccessible = true
        "OK (" + m.invoke(inner, pkg) + ")"
    } catch (t: Throwable) { err(t) } }

    private fun datacache(c: Context, pkg: String): String { return try {
        val bc = BypassCtx(c)
        val uid = bc.packageManager.getApplicationInfo(pkg, 0).uid
        if (Build.VERSION.SDK_INT >= 31) {
            val s = bc.getSystemService("byd_datacached") ?: return "byd_datacached não existe"
            "OK (" + s.javaClass.getMethod("setAppStartupData", String::class.java, Int::class.javaPrimitiveType).invoke(s, uid.toString(), 0) + ")"
        } else {
            val s = bc.getSystemService("bg_datacache") ?: return "bg_datacache não existe"
            "OK (" + s.javaClass.getMethod("setAppOpsData", String::class.java, Int::class.javaPrimitiveType).invoke(s, uid.toString(), 0) + ")"
        }
    } catch (t: Throwable) { err(t) } }

    /** Teste: a trava de ACC pedida pelo PRÓPRIO app (depois da lista branca), segura enquanto o app viver. */
    fun appAccLock(): String {
        val logs = StringBuilder()
        AccDaemon.logger = { logs.append(it).append('\n') }
        return try {
            val t = Binder()
            val ok = AccDaemon.acquire(t)
            if (ok) appLockToken = t
            (if (ok) "Trava de ACC pelo app: OK\n" else "Trava de ACC pelo app: falhou\n") + logs
        } finally { AccDaemon.logger = null }
    }
}
