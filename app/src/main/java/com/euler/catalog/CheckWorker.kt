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
            if (r.status == "revoked" && r.wipe) {
                Store.uninstallAll(ctx)
            } else if (r.status == "active") {
                // Auto-atualização do próprio Unlock BYD (qualquer rede).
                try { Updater.selfUpdateIfNeeded(ctx) } catch (e: Exception) {}
                // Outros apps instalados: só no Wi-Fi e 1x por dia (primeira internet do dia).
                try {
                    if (Updater.isUnmetered(ctx) && Updater.firstTimeToday(ctx)) {
                        Updater.updateInstalledApps(ctx)
                        Updater.markDoneToday(ctx)
                    }
                } catch (e: Exception) {}
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
