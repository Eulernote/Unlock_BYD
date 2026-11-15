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
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
