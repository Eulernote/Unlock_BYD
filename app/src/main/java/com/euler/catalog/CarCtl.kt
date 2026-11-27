package com.euler.catalog

import android.content.Context
import android.os.Looper

/**
 * CONTROLES DO CARRO (teste v73) — trancar, destrancar, piscar, ar-condicionado, janelas.
 *
 * Roda como processo do shell (pelo ADB local, com app_process), igual ao Sentry: as APIs da BYD que
 * mudam algo no carro só aceitam chamadas de processos com mais privilégio que um app comum.
 * Uso: app_process /system/bin com.euler.catalog.CarCtl comando args
 * Responde uma linha "UBYD_RES ..." com o resultado.
 *
 * Fonte dos códigos: análise do Sentry (BYDAutoManager.setBuffer(1034, 0xAA000004, quadro) com o código
 * no byte 22: 7 = trancar, 5 = destrancar, 22 = piscar, 10 = achar o carro (pisca + buzina)).
 */
object CarCtl {
    /** Permissões da BYD que o app precisa (concedidas pelo ADB). O ar e as janelas rodam DENTRO do app, como no Sentry. */
    val PERMS = listOf("BYDAUTO_STATISTIC_GET", "BYDAUTO_BODYWORK_GET", "BYDAUTO_BMS_GET", "BYDAUTO_POWER_GET", "BYDAUTO_ENERGY_GET",
        "BYDAUTO_AC_GET", "BYDAUTO_AC_SET", "BYDAUTO_AC_COMMON", "BYDAUTO_BODYWORK_SET", "BYDAUTO_BODYWORK_COMMON",
        "BYDAUTO_DOORLOCK_GET", "BYDAUTO_DOOR_LOCK_GET", "BYDAUTO_LIGHT_GET", "BYDAUTO_LIGHT_SET")
    /** Comandos que precisam do shell (quadro bruto no BYDAutoManager). O resto roda no app. */
    val SHELL_CMDS = setOf("lock", "unlock", "flash", "find", "buf")

    /** Executa no próprio processo do app (ar, temperatura, ventilação, janelas, estado, métodos). */
    fun local(ctx: Context, args: String): String {
        val p = args.trim().split(Regex("\\s+"))
        return try { exec(BydUnlock.BypassCtx(ctx.applicationContext), p.firstOrNull() ?: "help", p.drop(1)) } catch (t: Throwable) { val c = t.cause ?: t; "ERRO ${c.javaClass.simpleName}: ${c.message}" }
    }

    private val FRAME = byteArrayOf(28, 2, 20, 0, -2, 20, 46, -126, 92, -15, 120, 80, 68, -94, -71, -54, 21, -93, -18, -68, -44, 123, 22, 0, 0, 0)
    private val DEV = mapOf(
        "ac" to "android.hardware.bydauto.ac.BYDAutoAcDevice",
        "body" to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
        "window" to "android.hardware.bydauto.window.BYDAutoWindowDevice",
        "lock" to "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
        "lock2" to "android.hardware.bydauto.doorlock.BYDAutoDoorlockDevice",
        "power" to "android.hardware.bydauto.power.BYDAutoPowerDevice",
        "light" to "android.hardware.bydauto.light.BYDAutoLightDevice",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val out = try {
            if (Looper.myLooper() == null) try { Looper.prepareMainLooper() } catch (_: Throwable) {}
            val ctx = context()
            exec(ctx, args.getOrNull(0) ?: "help", args.drop(1))
        } catch (t: Throwable) { val c = t.cause ?: t; "ERRO ${c.javaClass.simpleName}: ${c.message}" }
        println("UBYD_RES $out")
        System.out.flush()
        System.exit(0)
    }

    private fun context(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val main = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(main) as Context
    }

    private var lastDevErr = ""
    private fun dev(ctx: Context, key: String): Any? {
        val cls = DEV[key] ?: key
        return try { Class.forName(cls).getMethod("getInstance", Context::class.java).invoke(null, ctx).also { if (it == null) lastDevErr = "getInstance devolveu null" } }
        catch (t: Throwable) { val c = t.cause ?: t; lastDevErr = "${c.javaClass.simpleName}: ${c.message}"; null }
    }

    /** Chama o método <name> com N inteiros. Mostra o retorno (0 costuma ser sucesso). */
    private fun call(obj: Any?, name: String, vararg a: Int): String {
        if (obj == null) return "$name: aparelho não abriu ($lastDevErr)"
        val m = obj.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == a.size && it.parameterTypes.all { p -> p == Int::class.javaPrimitiveType } }
            ?: return "$name(${a.size} args): método não existe"
        return try { "$name(${a.joinToString(",")})=" + m.invoke(obj, *a.map { it as Any }.toTypedArray()) }
        catch (t: Throwable) { val c = t.cause ?: t; "$name: ${c.javaClass.simpleName} ${c.message}" }
    }

    private fun get(obj: Any?, name: String, vararg a: Int): String = call(obj, name, *a).substringAfter("=", "?")

    private fun buf(ctx: Context, code: Int): String {
        val mgr = ctx.getSystemService("auto") ?: return "sem BYDAutoManager"
        val b = FRAME.clone(); b[0] = 94; b[22] = code.toByte()
        return try {
            val m = mgr.javaClass.getMethod("setBuffer", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java)
            "setBuffer($code)=" + m.invoke(mgr, 1034, 0xAA000004L.toInt(), b)
        } catch (t: Throwable) { val c = t.cause ?: t; "setBuffer: ${c.javaClass.simpleName} ${c.message}" }
    }

    private fun exec(ctx: Context, cmd: String, a: List<String>): String {
        val ints = a.mapNotNull { it.toIntOrNull() }
        return when (cmd) {
            "lock" -> buf(ctx, 7)
            "unlock" -> buf(ctx, 5)
            "flash" -> buf(ctx, 22)
            "find" -> buf(ctx, 10)
            "ac_on" -> { val ac = dev(ctx, "ac"); call(ac, "start", 0) + " · estado=" + get(ac, "getAcStartState") }
            "ac_off" -> { val ac = dev(ctx, "ac"); call(ac, "stop", 0) + " · estado=" + get(ac, "getAcStartState") }
            "temp" -> { // tenta as variações conhecidas e mostra a leitura depois de cada uma
                val ac = dev(ctx, "ac"); val t = ints.firstOrNull() ?: 22
                listOf(intArrayOf(0, t, 0, 1), intArrayOf(1, t, 0, 1), intArrayOf(0, t), intArrayOf(1, t)).joinToString(" | ") {
                    call(ac, "setAcTemperature", *it).also { Thread.sleep(300) } + " → lido " + get(ac, "getAcTemperature", 1)
                }
            }
            "fan" -> { val ac = dev(ctx, "ac"); val n = ints.firstOrNull() ?: 3; call(ac, "setAcWindLevel", 0, n) + " · lido " + get(ac, "getAcWindLevel") }
            "win" -> { // win <comando 1=abrir 2=fechar 3=parar> [área 0=todas]
                val body = dev(ctx, "body"); val c = ints.getOrNull(0) ?: 3; val area = ints.getOrNull(1) ?: 0
                call(body, "setBodyWindowCtrlState", area, c) + " | " + call(body, "setBodyWindowCtrlState", c, area)
            }
            "state" -> {
                val ac = dev(ctx, "ac"); val body = dev(ctx, "body"); val l1 = dev(ctx, "lock") ?: dev(ctx, "lock2")
                "ar=" + get(ac, "getAcStartState") + " temp=" + get(ac, "getAcTemperature", 1) + " vent=" + get(ac, "getAcWindLevel") +
                    " trava=" + get(l1, "getDoorlockState", 1) + " sistema=" + get(body, "getAutoSystemState")
            }
            "methods" -> { // methods <aparelho>: lista os métodos set/get
                val o = dev(ctx, a.getOrNull(0) ?: "ac") ?: return "aparelho não existe"
                o.javaClass.methods.map { it.name + "(" + it.parameterTypes.size + ")" }.filter { it.startsWith("set") || it.startsWith("get") || it == "start(1)" || it == "stop(1)" }.distinct().sorted().joinToString(" ")
            }
            "call" -> call(dev(ctx, a.getOrNull(0) ?: ""), a.getOrNull(1) ?: "", *a.drop(2).mapNotNull { it.toIntOrNull() }.toIntArray())
            "buf" -> buf(ctx, ints.firstOrNull() ?: 22)
            else -> "comandos: lock unlock flash find ac_on ac_off temp N fan N win C [área] state methods <ac|body|window|lock|power> call <aparelho> <método> ints... buf N"
        }
    }
}
