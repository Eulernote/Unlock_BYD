package com.euler.catalog

import android.content.Context
import java.io.File

/** Registro de eventos do vigia (v82): o que acontece quando o carro desliga. Lido no menu escondido. */
object VLog {
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "vigia_eventos.log")
    @Synchronized fun add(ctx: Context, msg: String) {
        try {
            val f = file(ctx)
            val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg + "\n"
            if (f.exists() && f.length() > 40_000) { val keep = f.readLines().takeLast(150); f.writeText(keep.joinToString("\n") + "\n") }
            f.appendText(line)
        } catch (_: Exception) {}
    }
    fun read(ctx: Context): String = try { file(ctx).readLines().takeLast(60).joinToString("\n").ifBlank { "(vazio)" } } catch (_: Exception) { "(vazio)" }
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) {} }
}
