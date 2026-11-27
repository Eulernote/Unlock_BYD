package com.euler.catalog.assistant

import android.content.Context
import android.util.Log

/**
 * Controle do carro pela API escondida da BYD (android.hardware.bydauto.*),
 * chamada por reflection — não precisa do SDK da BYD em tempo de compilação.
 *
 * Mecanismos extraídos do Electro, Overdrive e SentryEV:
 *  - Ar / vidros: device.getInstance(ctx) + método (start/stop, setAcTemperature,
 *    setAcWindLevel, setAllWindowState).
 *  - Ligar/desligar/travar: serviço de sistema "auto" + setBuffer(1034, 0xAA000004, frame),
 *    com o byte 22 do frame = código da ação.
 *
 * Este é o caminho CLÁSSICO (DiLink 3/4), que é o do carro de testes (Dolphin Mini).
 * O caminho DiLink5 (carros novos) entra depois, quando houver um carro assim pra testar.
 * Tudo é best-effort: se o método não existe naquele modelo, devolve false sem quebrar.
 */
object BydControl {

    private const val TAG = "BydControl"

    // Frame da chave remota (26 bytes). Byte 0 = 0x5E (94), byte 22 = código.
    // Idêntico ao usado por Overdrive e SentryEV.
    private val REMOTE_FRAME = byteArrayOf(
        94, 2, 20, 0, -2, 20, 46, -126, 92, -15, 120, 80, 68, -94, -71, -54,
        21, -93, -18, -68, -44, 123, 0, 0, 0, 0
    )
    private const val REMOTE_DEVICE = 1034
    private const val REMOTE_CMD = -1442840572 // 0xAA000004

    private fun device(ctx: Context, className: String): Any? = try {
        Class.forName(className).getMethod("getInstance", Context::class.java).invoke(null, ctx)
    } catch (e: Throwable) {
        Log.w(TAG, "device $className indisponivel: ${e.message}")
        null
    }

    /** Invoca um método de N ints no device; true se retornou 0 (sucesso BYD) ou void. */
    private fun callInt(dev: Any?, method: String, vararg args: Int): Boolean {
        if (dev == null) return false
        return try {
            val types = Array(args.size) { Integer.TYPE }
            val boxed = args.map { it as Any }.toTypedArray()
            val r = dev.javaClass.getMethod(method, *types).invoke(dev, *boxed)
            when (r) {
                is Int -> r == 0
                is Boolean -> r
                else -> true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "$method falhou: ${e.message}")
            false
        }
    }

    // --- Ar-condicionado (BYDAutoAcDevice) ---
    private fun ac(ctx: Context) = device(ctx, "android.hardware.bydauto.ac.BYDAutoAcDevice")

    fun acStart(ctx: Context) = callInt(ac(ctx), "start", 0)
    fun acStop(ctx: Context) = callInt(ac(ctx), "stop", 0)

    /** Temperatura: zona 0=ambas,1=motorista,2=passageiro; unidade 1=Celsius. Faixa 16–33. */
    fun acTemperature(ctx: Context, zone: Int, celsius: Int): Boolean {
        val c = celsius.coerceIn(16, 33)
        return callInt(ac(ctx), "setAcTemperature", zone, c, 0, 1)
    }

    /** Ventilação 1–7. */
    fun acWindLevel(ctx: Context, level: Int): Boolean {
        if (level !in 1..7) return false
        return callInt(ac(ctx), "setAcWindLevel", 0, level)
    }

    // --- Vidros (BYDAutoBodyworkDevice) ---
    private fun bodywork(ctx: Context) = device(ctx, "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")

    /** Estados por janela (df, pf, dt, pt): 1=abrir, 2=fechar, 3=parar. */
    fun setWindows(ctx: Context, df: Int, pf: Int, dt: Int, pt: Int) =
        callInt(bodywork(ctx), "setAllWindowState", df, pf, dt, pt)

    // --- Chave remota (ligar/desligar/travar) via serviço "auto" ---
    fun remoteKey(ctx: Context, code: Int): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val auto = ctx.getSystemService("auto") ?: run {
                Log.w(TAG, "servico 'auto' indisponivel"); return false
            }
            val frame = REMOTE_FRAME.clone()
            frame[22] = code.toByte()
            val m = auto.javaClass.getMethod(
                "setBuffer", Integer.TYPE, Integer.TYPE, ByteArray::class.java
            )
            val r = m.invoke(auto, REMOTE_DEVICE, REMOTE_CMD, frame)
            (r as? Int)?.let { it == 0 } ?: true
        } catch (e: Throwable) {
            Log.w(TAG, "remoteKey($code) falhou: ${e.message}")
            false
        }
    }

    /**
     * Ligar o veículo: sequência que o Overdrive usa — manda 9 (primer de desligar),
     * espera 2s, e então manda 1. É o que faz o power-on funcionar.
     */
    fun powerOn(ctx: Context): Boolean {
        remoteKey(ctx, 9)
        try { Thread.sleep(2000) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); return false
        }
        return remoteKey(ctx, 1)
    }

    fun powerOff(ctx: Context) = remoteKey(ctx, 9)
}
