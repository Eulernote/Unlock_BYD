package com.euler.catalog

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * VIGIA (v72): com o monitoramento liberado, a multimídia fica acordada sem limite de tempo, mesmo com o
 * carro desligado, para o cliente poder abrir as câmeras a qualquer hora. Em espera só fica a conexão com
 * o servidor; câmera e vídeo ligam apenas quando o cliente pede.
 *
 * Proteções (decididas com o Euler):
 *  - 12V abaixo de 12,6 V por 10 minutos seguidos → solta a multimídia (ela dorme). Aviso já em 12,8 V.
 *  - Tração abaixo de 5% → solta a multimídia e as câmeras ficam bloqueadas.
 *  - Volta sozinho quando o carro é ligado/carregado (12V ≥ 12,9 V e tração ≥ 6%).
 *  - Sem leitura de bateria = sem batimento → o processo da vigia se solta sozinho em 15 min.
 *
 * Chamado pelo MonitorService a cada leitura de telemetria (2 min).
 */
object Vigia {
    const val V12_WARN = 12.8
    const val V12_CUT = 12.6
    const val SOC_MIN = 5.0
    private const val V12_REARM = 12.9
    private const val LOW_FOR_MS = 10 * 60_000L
    private const val CHECK_EVERY_MS = 9 * 60_000L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("vigia", Context.MODE_PRIVATE)
    fun hbFile(ctx: Context) = File(ctx.applicationContext.getExternalFilesDir(null) ?: ctx.applicationContext.filesDir, "vigia_hb")
    private fun writeHb(ctx: Context, s: String) { try { hbFile(ctx).writeText(s + " " + System.currentTimeMillis()) } catch (_: Exception) {} }

    /** Resultado do último wakeUpMcu (laço do MonitorService). */
    @Volatile var wakeOk = false
    @Volatile var wakeLast = ""
    fun armed(ctx: Context) = (prefs(ctx).getString("cut", "") ?: "").isEmpty()

    /** Tração abaixo do mínimo na última leitura → câmeras bloqueadas. */
    fun socLow(ctx: Context): Boolean { val s = prefs(ctx).getFloat("soc", -1f); return s >= 0f && s < SOC_MIN }

    /**
     * Recebe a leitura (JSON do Telemetry) e devolve o estado para mandar ao servidor:
     * on · cut_v12 · cut_soc · noadb (sem ADB, não dá pra segurar) · fail · notele (sem leitura) · ?
     */
    @Synchronized
    fun tick(ctx: Context, t: JSONObject): String {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val soc = if (t.has("soc")) t.optDouble("soc", Double.NaN) else Double.NaN
        val v12 = if (t.has("v12")) t.optDouble("v12", Double.NaN) else Double.NaN
        if (soc.isNaN() && v12.isNaN()) return "notele" // não escreve batimento: a vigia se solta em 15 min
        val ed = p.edit()
        if (!soc.isNaN()) ed.putFloat("soc", soc.toFloat())
        var low = p.getLong("low12", 0L)
        if (!v12.isNaN()) {
            if (v12 < V12_CUT) { if (low == 0L) { low = now; ed.putLong("low12", now) } }
            else if (low != 0L) { low = 0L; ed.putLong("low12", 0L) }
        }
        ed.apply()

        var cut = p.getString("cut", "") ?: ""
        if (cut.isEmpty()) {
            cut = when {
                !soc.isNaN() && soc < SOC_MIN -> "soc"
                low > 0L && now - low >= LOW_FOR_MS -> "v12"
                else -> ""
            }
            if (cut.isNotEmpty()) p.edit().putString("cut", cut).putLong("cutAt", now).putLong("chk", 0L).apply()
        } else {
            val socOk = soc.isNaN() || soc >= SOC_MIN + 1
            val v12Ok = v12.isNaN() || v12 >= V12_REARM
            if (socOk && v12Ok) { cut = ""; p.edit().putString("cut", "").putLong("low12", 0L).putLong("chk", 0L).apply() }
        }
        if (cut.isNotEmpty()) { writeHb(ctx, "stop $cut"); return "cut_$cut" }

        writeHb(ctx, "ok")
        // v81: mantém acordada pelo método do Electro (wakeUpMcu em laço, no MonitorService). A trava de ACC
        // pelo ADB exige DEVICE_ACC, que o shell não tem nos carros novos — fica só como tentativa extra.
        if (wakeOk) return "on"
        var st = p.getString("st", "?") ?: "?"
        if (now - p.getLong("chk", 0L) >= CHECK_EVERY_MS) {
            p.edit().putLong("chk", now).apply()
            st = when (try { AdbShell.vigiaRunning(ctx) } catch (_: Exception) { null }) {
                true -> "on"
                null -> "noadb"
                false -> if (try { AdbShell.startVigia(ctx, 0, hbFile(ctx).absolutePath).ok } catch (_: Exception) { false }) "on" else "fail"
            }
            p.edit().putString("st", st).apply()
        }
        return st
    }

    /** Solta a multimídia (ela volta a dormir normalmente com o carro desligado). */
    fun release(ctx: Context, why: String) {
        writeHb(ctx, "stop $why")
        try { AdbShell.stopVigia(ctx) } catch (_: Exception) {}
        prefs(ctx).edit().putString("st", "off").putLong("chk", 0L).apply()
    }
}
