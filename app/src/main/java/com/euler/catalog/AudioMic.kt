package com.euler.catalog

import android.content.Context
import android.util.Log
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Microfone da cabine: a captura roda no processo do shell (MicShell, uid 2000), que tem privilégio de
 * áudio que o app comum não tem. O shell manda o PCM 48 kHz por um socket local; aqui reduzimos para
 * 16 kHz e enviamos pelo canal de dados do WebRTC. Canal shell->app por TCP em 127.0.0.1.
 */
object AudioMic {
    private const val TAG = "UBYD-mic"
    private const val RATE = 48000
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    /** Devolve "ok …" quando o áudio começa a chegar, ou uma mensagem de erro. */
    fun start(ctx: Context, dc: DataChannel): String {
        stop()
        val srv = try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", 0)) } } catch (t: Throwable) { return "não consegui abrir o canal de áudio: ${t.message}" }
        server = srv
        val port = srv.localPort
        // Lança a captura no shell (uid 2000).
        try {
            val apk = ctx.applicationInfo.sourceDir
            AdbShell.run(ctx, "pkill -f com.euler.catalog.MicShell 2>/dev/null; (CLASSPATH=$apk setsid nohup app_process /system/bin com.euler.catalog.MicShell $port $RATE >/dev/null 2>&1 &); echo ok", 15000)
        } catch (t: Throwable) { closeServer(); return "não consegui iniciar a captura: ${t.message}" }

        // Espera o shell conectar (até ~8 s).
        val sock: Socket = try {
            srv.soTimeout = 8000
            srv.accept()
        } catch (t: Throwable) { closeServer(); return micLogTail(ctx) ?: "a captura não respondeu (microfone pode estar bloqueado)" }
        closeServer()

        running = true
        thread = Thread {
            val ins = try { sock.getInputStream() } catch (t: Throwable) { return@Thread }
            val buf = ByteArray(3840)
            var firstSoundSeen = false
            try {
                while (running) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    if (dc.state() != DataChannel.State.OPEN) break
                    if (!firstSoundSeen) firstSoundSeen = true
                    sendDown(dc, buf, n)
                }
            } catch (_: Throwable) {} finally {
                try { sock.close() } catch (_: Throwable) {}
            }
        }.apply { isDaemon = true; start() }

        return "ok"
    }

    /** Reduz 48 kHz -> 16 kHz (média de 3 amostras) e envia PCM 16 bits LE. */
    private fun sendDown(dc: DataChannel, data: ByteArray, len: Int) {
        val inSh = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val nIn = inSh.remaining()
        val out = ByteBuffer.allocate((nIn / 3) * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 3 <= nIn) {
            val acc = inSh.get(i).toInt() + inSh.get(i + 1).toInt() + inSh.get(i + 2).toInt()
            out.putShort((acc / 3).toShort()); i += 3
        }
        out.flip()
        try { dc.send(DataChannel.Buffer(out, true)) } catch (_: Throwable) {}
    }

    private fun micLogTail(ctx: Context): String? = try {
        val r = AdbShell.run(ctx, "tail -n 3 /data/local/tmp/ubyd_mic.log 2>/dev/null", 8000).output.trim()
        if (r.isBlank()) null else "microfone: " + r.lines().lastOrNull()
    } catch (_: Throwable) { null }

    private fun closeServer() { try { server?.close() } catch (_: Throwable) {}; server = null }

    fun stop() {
        running = false
        thread = null
        closeServer()
        try { AdbShell.run(appCtx(), "pkill -f com.euler.catalog.MicShell 2>/dev/null; echo ok", 8000) } catch (_: Throwable) {}
    }

    private fun appCtx(): Context = LiveCamCtx.ctx ?: throw IllegalStateException("sem contexto")
}

/** Guarda o contexto para o AudioMic poder encerrar a captura do shell. */
object LiveCamCtx { @Volatile var ctx: Context? = null }
