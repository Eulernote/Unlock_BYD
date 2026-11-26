package com.euler.catalog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Carro ligou (ou o app acabou de ser atualizado): faz uma checagem logo que houver internet
 * e garante que a checagem periódica (15 min) continua agendada.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (MonitorService.enabled(ctx)) MonitorService.sync(ctx, true)
        try {
            if (!Store.isOnboarded(ctx)) return
            schedule(ctx.applicationContext, now = true)
        } catch (e: Exception) {}
    }

    companion object {
        fun schedule(ctx: Context, now: Boolean) {
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val wm = WorkManager.getInstance(ctx)
            wm.enqueueUniquePeriodicWork("ubyd-check", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(CheckWorker::class.java, 15, TimeUnit.MINUTES).setConstraints(net).build())
            if (now) wm.enqueueUniqueWork("ubyd-check-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(CheckWorker::class.java).setConstraints(net).setInitialDelay(20, TimeUnit.SECONDS).build())
        }
    }
}
