package com.euler.catalog

import android.content.Context

/**
 * Tenta ligar o início automático pela API de hardware do BYD, por reflexão.
 * É um REFORÇO opcional (o caminho principal é o AccessibilityService). Como a assinatura
 * exata varia por firmware, aqui tudo é best-effort e totalmente protegido: se não existir
 * ou falhar, não faz nada e não quebra.
 *
 * Referência: o Overdrive usa android.hardware.bydauto.BYDAutoDeviceManager.getInstance(ctx)
 * e grava o startup via setAppStartupData/autoStartBoot.
 */
object BydAutostart {

    fun trySet(ctx: Context, enable: Boolean): Boolean {
        return try {
            val pkg = ctx.packageName
            val mgrClass = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
            val getInstance = mgrClass.getMethod("getInstance", Context::class.java)
            val mgr = getInstance.invoke(null, ctx) ?: return false

            // 1) Procura direto no manager um método de startup que receba o pacote.
            if (invokeStartup(mgr, pkg, enable)) return true

            // 2) Alguns firmwares expõem um "device" de startup separado.
            for (m in mgrClass.methods) {
                val n = m.name.lowercase()
                if (m.parameterTypes.isEmpty() && (n.contains("startup") || n.contains("appstart"))) {
                    val dev = try { m.invoke(mgr) } catch (e: Exception) { null } ?: continue
                    if (invokeStartup(dev, pkg, enable)) return true
                }
            }
            false
        } catch (e: Throwable) { false }
    }

    /** Invoca, no objeto, um método tipo setAppStartupData(pkg, true, ...) preenchendo bool=enable, int=1. */
    private fun invokeStartup(obj: Any, pkg: String, enable: Boolean): Boolean {
        for (m in obj.javaClass.methods) {
            val n = m.name.lowercase()
            if (!(n.contains("appstartup") || n.contains("autostart") || n.contains("startupdata") ||
                  (n.startsWith("set") && n.contains("startup")))) continue
            val types = m.parameterTypes
            if (types.isEmpty() || types[0] != String::class.java) continue
            val args = arrayOfNulls<Any>(types.size)
            args[0] = pkg
            for (i in 1 until types.size) {
                args[i] = when (types[i]) {
                    java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> enable
                    java.lang.Integer.TYPE, Integer::class.java -> if (enable) 1 else 0
                    String::class.java -> pkg
                    else -> null
                }
            }
            try { m.invoke(obj, *args); return true } catch (e: Exception) {}
        }
        return false
    }
}
