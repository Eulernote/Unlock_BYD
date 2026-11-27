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
 * Uso: app_process /system/bin com.euler.catalog.AccDaemon <minutos>
 * Log: /data/local/tmp/ubyd_vigia.log
 */
object AccDaemon {
    private val LOG = File("/data/local/tmp/ubyd_vigia.log")
    private fun log(s: String) {
        val line = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + s
        println(line)
        try { LOG.appendText(line + "\n") } catch (_: Throwable) {}
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val minutes = (args.getOrNull(0)?.toIntOrNull() ?: 10).coerceIn(1, 24 * 60)
        try { if (LOG.length() > 64_000) LOG.delete() } catch (_: Throwable) {}
        log("vigia iniciando (limite $minutes min, uid ${android.os.Process.myUid()})")
        val token = Binder()
        if (!acquire(token)) { log("FALHOU: não consegui a trava de ACC — saindo"); return }
        val end = System.currentTimeMillis() + minutes * 60_000L
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
            log("acordada · faltam ${(end - System.currentTimeMillis()) / 60_000} min")
        }
        log("tempo da vigia acabou — liberando (a multimídia pode dormir)")
        // Ao sair, o token morre e o accmodemanager solta a trava (mesmo comportamento do Sentry).
    }

    private fun service(): IBinder? {
        val sm = Class.forName("android.os.ServiceManager")
        val get = sm.getMethod("getService", String::class.java)
        for (n in listOf("accmodemanager", "acc_mode_manager", "AccModeManager")) {
            val b = try { get.invoke(null, n) as? IBinder } catch (_: Throwable) { null }
            if (b != null) { log("serviço encontrado: $n"); return b }
        }
        return null
    }

    /** Mesma chamada do Sentry: IAccModeManager, transação 5 (token, tipo 1, motivo, pacote, 0, null). */
    private fun acquire(token: Binder): Boolean {
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
