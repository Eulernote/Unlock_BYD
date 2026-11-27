package com.euler.catalog

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captura o microfone da cabine igual ao Sentry (AudioRecord cru a 48 kHz) e envia pelo canal de dados
 * da conexão WebRTC. A captura do WebRTC é recusada neste BYD; esta via é a que o Sentry usa e funciona.
 * Confirma o microfone pela LEITURA real (não só pelo estado) e reduz 48 kHz -> 16 kHz para economizar dados.
 */
object AudioMic {
    private const val TAG = "UBYD-mic"
    private val SOURCES = intArrayOf(6, 1, 5, 7, 0) // VOICE_RECOGNITION, MIC, CAMCORDER, VOICE_COMMUNICATION, DEFAULT
    private val RATES = intArrayOf(48000, 16000)
    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun start(ctx: Context, dc: DataChannel): String {
        stop()
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Thread { try { AdbShell.run(ctx, "pm grant ${ctx.packageName} android.permission.RECORD_AUDIO; echo ok", 15000) } catch (_: Throwable) {} }.start()
            return "o app não tinha permissão de microfone — concedi agora, toque em Ouvir de novo"
        }
        var lastErr = "nenhuma fonte de microfone funcionou"
        for (rate in RATES) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val bufSize = Math.max(minBuf * 4, 16384)
            for (src in SOURCES) {
                val rec = try {
                    @Suppress("DEPRECATION")
                    AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                } catch (t: Throwable) { lastErr = "fonte $src@$rate: ${t.message}"; continue }
                if (rec.state != AudioRecord.STATE_INITIALIZED) { lastErr = "fonte $src@$rate: não inicializou"; rel(rec); continue }
                try { rec.startRecording() } catch (t: Throwable) { lastErr = "fonte $src@$rate: ${t.message}"; rel(rec); continue }
                // Confirma pela leitura real: tenta ler por até ~1,5 s; se vier áudio, está gravando.
                val probe = ByteArray(960)
                var got = -1
                var tries = 0
                while (tries < 30) {
                    val n = rec.read(probe, 0, probe.size)
                    if (n > 0) { got = n; break }
                    if (n < 0 && n != AudioRecord.ERROR_INVALID_OPERATION) { got = n; break }
                    try { Thread.sleep(50) } catch (_: InterruptedException) {}
                    tries++
                }
                if (got <= 0) { lastErr = "fonte $src@$rate: não engatou"; try { rec.stop() } catch (_: Throwable) {}; rel(rec); continue }
                Log.i(TAG, "microfone ok na fonte $src @ $rate Hz")
                running = true
                val down = rate / 16000 // 48000 -> 3 ; 16000 -> 1
                thread = Thread {
                    val inBuf = ByteArray(if (rate == 48000) 1920 else 640) // ~20 ms
                    try {
                        // manda o primeiro bloco já lido (probe) e segue
                        sendDown(dc, probe, got, down)
                        while (running) {
                            val n = rec.read(inBuf, 0, inBuf.size)
                            if (n <= 0) { if (n == AudioRecord.ERROR_DEAD_OBJECT) break else continue }
                            if (dc.state() != DataChannel.State.OPEN) break
                            if (!sendDown(dc, inBuf, n, down)) break
                        }
                    } finally { try { rec.stop() } catch (_: Throwable) {}; rel(rec) }
                }.apply { isDaemon = true; start() }
                return "ok (fonte $src @ $rate Hz)"
            }
        }
        return lastErr
    }

    /** Reduz para 16 kHz (média de 'down' amostras) e envia como PCM 16 bits LE pelo canal de dados. */
    private fun sendDown(dc: DataChannel, data: ByteArray, len: Int, down: Int): Boolean {
        val inSh = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val nIn = inSh.remaining()
        val out = ByteBuffer.allocate((nIn / down) * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + down <= nIn) {
            var acc = 0
            for (k in 0 until down) acc += inSh.get(i + k).toInt()
            out.putShort((acc / down).toShort())
            i += down
        }
        out.flip()
        return try { dc.send(DataChannel.Buffer(out, true)); true } catch (_: Throwable) { false }
    }

    private fun rel(rec: AudioRecord) { try { rec.release() } catch (_: Throwable) {} }

    @Synchronized
    fun stop() { running = false; thread = null }
}
