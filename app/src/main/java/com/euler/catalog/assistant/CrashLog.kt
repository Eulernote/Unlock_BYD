package com.euler.catalog.assistant

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captura crashes do assistente e grava num arquivo, pra aparecer na tela de teste
 * na próxima abertura (como não temos o log do carro, é assim que vemos o erro real).
 */
object CrashLog {

    private fun file(ctx: Context) = File(ctx.filesDir, "heyup_crash.log")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write(app, "UNCAUGHT [${t.name}]", e) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun write(ctx: Context, tag: String, e: Throwable) {
        try {
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            file(ctx).appendText("[$ts] $tag\n$sw\n\n")
        } catch (_: Throwable) {}
    }

    private fun stageFile(ctx: Context) = File(ctx.filesDir, "heyup_stage.log")

    /** Marca a última etapa alcançada (pra pinpoint de crash nativo, que não gera stacktrace Java). */
    fun stage(ctx: Context, label: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            stageFile(ctx).writeText("[$ts] $label")
        } catch (_: Throwable) {}
    }

    fun readStage(ctx: Context): String? = try {
        val f = stageFile(ctx)
        if (f.exists() && f.length() > 0) f.readText() else null
    } catch (_: Throwable) { null }

    fun readLast(ctx: Context): String? = try {
        val f = file(ctx)
        if (f.exists() && f.length() > 0) f.readText().takeLast(2500) else null
    } catch (_: Throwable) { null }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Throwable) {} }
}
