package com.euler.catalog

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Checagem periódica (1x/dia, quando houver internet): aplica revogação/remoção. */
class CheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            val ctx = applicationContext
            val r = Store.register(ctx)
            if (r.status == "revoked") {
                // Travado: volta o instalador original da BYD (Lock) e, se pedido, apaga os apps da loja.
                Store.applyRevoke(ctx, r.wipe)
            } else if (r.status == "active") {
                // Depois de uma atualização do app, o BYD apaga o auto-start: re-habilita sozinho (via ADB).
                try { Store.ensureAutostartAfterUpdate(ctx) } catch (e: Exception) {}
                // Canal de teste: se este carro foi marcado no painel, atualiza pra versão de teste.
                try { Updater.testUpdateIfNeeded(ctx, r.testVc, r.testUrl) } catch (e: Exception) {}
                // Auto-atualização do próprio Unlock BYD (qualquer rede).
                try { Updater.selfUpdateIfNeeded(ctx) } catch (e: Exception) {}
                // Outros apps instalados: sempre que estiver no Wi-Fi (a cada checagem ~15 min).
                try { if (Updater.isUnmetered(ctx)) Updater.updateInstalledApps(ctx) } catch (e: Exception) {}
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
