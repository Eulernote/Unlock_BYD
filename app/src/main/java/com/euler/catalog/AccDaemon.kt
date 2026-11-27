package com.euler.catalog

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.File

/**
 * VIGIA (teste v70): segura a multimídia "acordada" com o carro desligado.
 *
 * Roda FORA do app, como processo do shell (iniciado pelo ADB local com app_process), igual ao
 * Sentry/Overdrive: pede ao serviço da BYD "accmodemanager" uma trava de ACC — enquanto este processo
 * estiver vivo, a multimídia se comporta como se o carro estivesse ligado (não dorme, mantém Wi-Fi/dados).
 * Ao terminar (tempo limite ou pkill), o processo morre e a trava cai junto: a multimídia dorme normal.
 *
 * Uso: app_process /system/bin com.euler.catalog.AccDaemon <minutos (0 = sem limite)> [arquivo de batimento]
 *
 * Trava de segurança (v72): com o arquivo de batimento, o app escreve nele a cada 2 min ("ok" ou "stop").
 * Se aparecer "stop", ou se o app parar de escrever por mais de 15 min (app travado/desinstalado),
 * a vigia se solta sozinha — a multimídia nunca fica presa acordada sem o app vigiando a bateria.
 * Log: /data/local/tmp/ubyd_vigia.log
 */
object AccDaemon {
    private val LOG = File("/data/local/tmp/ubyd_vigia.log")
    private fun log(s: String) {
        logger?.let { it(s); return }
        val line = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + s
        println(line)
        try { LOG.appendText(line + "\n") } catch (_: Throwable) {}
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val minutes = (args.getOrNull(0)?.toIntOrNull() ?: 10).coerceIn(0, 24 * 60)
        val hb = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { File(it) }
        try { if (LOG.length() > 64_000) LOG.delete() } catch (_: Throwable) {}
        log("vigia iniciando (" + (if (minutes == 0) "sem limite" else "limite $minutes min") + ", uid ${android.os.Process.myUid()}" + (if (hb != null) ", batimento ${hb.path}" else "") + ")")
        val token = Binder()
        if (!acquire(token)) { log("FALHOU: não consegui a trava de ACC — saindo"); return }
        val start = System.currentTimeMillis()
        val end = if (minutes == 0) Long.MAX_VALUE else start + minutes * 60_000L
        var lastLog = 0L
        var seen = false // só cobra o batimento depois de conseguir ler o arquivo uma vez
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
            val now = System.currentTimeMillis()
            if (hb != null) {
                val txt = try { if (hb.exists()) hb.readText().trim() else null } catch (_: Throwable) { null }
                if (txt != null && !seen) { seen = true; log("batimento do app lido: ok") }
                val age = if (hb.exists()) now - hb.lastModified() else now - start
                if (txt != null && txt.startsWith("stop")) { log("o app pediu para parar ($txt) — liberando"); return }
                if (seen && age > 15 * 60_000L) { log("sem batimento do app há ${age / 60_000} min — liberando por segurança"); return }
            }
            if (now - lastLog > (if (minutes == 0) 10 * 60_000L else 60_000L)) {
                lastLog = now
                log(if (minutes == 0) "acordada há ${(now - start) / 60_000} min" else "acordada · faltam ${(end - now) / 60_000} min")
            }
        }
        log("tempo da vigia acabou — liberando (a multimídia pode dormir)")
        // Ao sair, o token morre e o accmodemanager solta a trava (mesmo comportamento do Sentry).
    }

    var logger: ((String) -> Unit)? = null
    internal fun service(): IBinder? {
        val sm = Class.forName("android.os.ServiceManager")
        val get = sm.getMethod("getService", String::class.java)
        for (n in listOf("accmodemanager", "acc_mode_manager", "AccModeManager")) {
            val b = try { get.invoke(null, n) as? IBinder } catch (_: Throwable) { null }
            if (b != null) { log("serviço encontrado: $n"); return b }
        }
        return null
    }

    /** Mesma chamada do Sentry: IAccModeManager, transação 5 (token, tipo 1, motivo, pacote, 0, null). */
    internal fun acquire(token: Binder): Boolean {
        val b = try { service() } catch (t: Throwable) { log("ServiceManager: ${t.message}"); null } ?: run { log("accmodemanager não existe neste carro"); return false }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("android.os.IAccModeManager")
            data.writeStrongBinder(token)
            data.writeInt(1)
            data.writeString("UnlockBYD:Vigia")
            data.writeString("com.euler.catalog")
            data.writeInt(0)
            data.writeString(null)
            val ok = b.transact(5, data, reply, 0)
            try { reply.readException(); log("trava de ACC: OK (transact=$ok)") } catch (t: Throwable) { log("trava de ACC: exceção do sistema: ${t.message}"); return false }
            ok
        } catch (t: Throwable) {
            log("trava de ACC: erro ${t.javaClass.simpleName}: ${t.message}"); false
        } finally { data.recycle(); reply.recycle() }
    }
}
