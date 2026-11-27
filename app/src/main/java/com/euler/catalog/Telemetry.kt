package com.euler.catalog

import android.content.Context
import org.json.JSONObject

/**
 * Telemetria da bateria (v71): % da bateria de tração (com casa decimal) e tensão da bateria de 12V,
 * lidas pela API da BYD (mesmas chamadas do Overdrive). Vai para o hub a cada 2 min junto com o
 * monitoramento, para o painel e o app do cliente mostrarem o gráfico de consumo (ex.: com a vigia ligada).
 */
object Telemetry {
    @Volatile var lastErr = ""
    private fun device(ctx: Context, cls: String): Pair<Class<*>, Any>? = try {
        val c = Class.forName(cls)
        val d = c.getMethod("getInstance", Context::class.java).invoke(null, BydUnlock.BypassCtx(ctx.applicationContext))
        if (d != null) c to d else { lastErr = cls.substringAfterLast('.') + ": null"; null }
    } catch (t: Throwable) { val e = t.cause ?: t; lastErr = cls.substringAfterLast('.') + ": " + e.javaClass.simpleName + " " + (e.message ?: "").take(120); null }

    private fun num(dev: Pair<Class<*>, Any>?, vararg names: String): Double? {
        dev ?: return null
        for (n in names) {
            val v = try { dev.first.getMethod(n).invoke(dev.second) } catch (_: Throwable) { null }
            if (v is Number) return v.toDouble()
        }
        return null
    }

    fun read(ctx: Context): JSONObject {
        val o = JSONObject().put("t", "tele")
        val stat = device(ctx, "android.hardware.bydauto.statistic.BYDAutoStatisticDevice")
        val body = device(ctx, "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")
        num(stat, "getElecPercentageValue")?.let { if (it > 0 && it <= 100) o.put("soc", Math.round(it * 10) / 10.0) }
        num(body, "getBatteryVoltage", "getBatteryPowerVoltage")?.let { raw ->
            // Alguns firmwares devolvem em décimos ou centésimos de volt.
            val v = when { raw > 100 -> raw / 100.0; raw > 20 -> raw / 10.0; else -> raw }
            if (v in 5.0..20.0) o.put("v12", Math.round(v * 100) / 100.0)
            o.put("v12raw", raw)
        }
        num(body, "getBatteryVoltageLevel")?.let { o.put("v12lvl", it.toInt()) }
        num(body, "getAutoSystemState")?.let { o.put("sys", it.toInt()) }
        // Este modelo não informa a tensão da 12V; só um aviso de "12V baixa" (registrado para análise).
        num(device(ctx, "android.hardware.bydauto.power.BYDAutoPowerDevice"), "getBatteryLowVoltageState")?.let { o.put("lv12", it.toInt()) }
        if (!o.has("soc") && !o.has("v12") && lastErr.isNotEmpty()) o.put("err", lastErr)
        return o
    }
}
