package com.euler.catalog

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import org.webrtc.DataChannel
import java.nio.ByteBuffer

/**
 * Captura o microfone da cabine igual ao Sentry (AudioRecord cru, com espera do microfone engatar) e
 * envia o áudio pelo canal de dados da conexão WebRTC que já existe — sem passar pela captura do WebRTC,
 * que é recusada neste BYD ("incorrect state"). PCM 16 bits, mono, 16 kHz.
 */
object AudioMic {
    private const val TAG = "UBYD-mic"
    private const val RATE = 16000
    private val SOURCES = intArrayOf(6, 1, 5, 7, 0) // VOICE_RECOGNITION, MIC, CAMCORDER, VOICE_COMMUNICATION, DEFAULT
    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun start(ctx: Context, dc: DataChannel): String {
        stop()
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Thread { try { AdbShell.run(ctx, "pm grant ${ctx.packageName} android.permission.RECORD_AUDIO; echo ok", 15000) } catch (_: Throwable) {} }.start()
            return "o app não tinha permissão de microfone — concedi agora, toque em Ouvir de novo"
        }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = Math.max(minBuf * 4, 8192)
        var lastErr = "nenhuma fonte de microfone funcionou"
        for (src in SOURCES) {
            val rec = try {
                @Suppress("DEPRECATION")
                AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            } catch (t: Throwable) { lastErr = "fonte $src: ${t.message}"; continue }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { lastErr = "fonte $src: não inicializou"; try { rec.release() } catch (_: Throwable) {}; continue }
            try { rec.startRecording() } catch (t: Throwable) { lastErr = "fonte $src: ${t.message}"; try { rec.release() } catch (_: Throwable) {}; continue }
            // Espera o microfone engatar (como o Sentry: até ~1 s).
            var ok = false
            for (i in 0 until 20) { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { ok = true; break }; try { Thread.sleep(50) } catch (_: InterruptedException) {} }
            if (!ok) { lastErr = "fonte $src: não engatou"; try { rec.stop(); rec.release() } catch (_: Throwable) {}; continue }
            Log.i(TAG, "microfone ok na fonte $src")
            running = true
            thread = Thread {
                val buf = ByteArray(640) // 20 ms a 16 kHz mono 16 bits
                try {
                    while (running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) { if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) break else continue }
                        if (dc.state() != DataChannel.State.OPEN) break
                        try { dc.send(DataChannel.Buffer(ByteBuffer.wrap(buf.copyOf(n)), true)) } catch (_: Throwable) { break }
                    }
                } finally { try { rec.stop() } catch (_: Throwable) {}; try { rec.release() } catch (_: Throwable) {} }
            }.apply { isDaemon = true; start() }
            return "ok (fonte $src)"
        }
        return lastErr
    }

    @Synchronized
    fun stop() {
        running = false
        thread = null
    }
}
