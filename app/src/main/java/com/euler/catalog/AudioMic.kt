package com.euler.catalog

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captura o microfone da cabine (AudioRecord cru, 48 kHz, como o Sentry) e envia pelo canal de dados.
 * Antes de gravar: libera o AppOps de gravação (separado da permissão) e tira o mudo do microfone —
 * sem isso o Android entrega silêncio ou nada, mesmo com a permissão concedida.
 * Mede o áudio real (não só o estado) e, se vier só silêncio, avisa que o sistema está bloqueando.
 */
object AudioMic {
    private const val TAG = "UBYD-mic"
    private val SOURCES = intArrayOf(6, 1, 5, 7, 0) // VOICE_RECOGNITION, MIC, CAMCORDER, VOICE_COMMUNICATION, DEFAULT
    private val RATES = intArrayOf(48000, 16000)
    @Volatile private var running = false
    @Volatile private var prepared = false
    private var thread: Thread? = null

    private fun prepare(ctx: Context) {
        try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMicrophoneMute = false } catch (_: Throwable) {}
        if (prepared) return
        prepared = true
        try {
            val pkg = ctx.packageName
            AdbShell.run(ctx,
                "appops set $pkg RECORD_AUDIO allow 2>/dev/null; appops set $pkg android:record_audio allow 2>/dev/null; " +
                "cmd appops set $pkg RECORD_AUDIO allow 2>/dev/null; appops set $pkg RECORD_AUDIO_HOTWORD allow 2>/dev/null; " +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>/dev/null; echo ok", 15000)
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun start(ctx: Context, dc: DataChannel): String {
        stop()
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prepared = false; prepare(ctx)
            return "liberei a permissão do microfone agora — toque em Ouvir de novo"
        }
        prepare(ctx)
        var diag = "sem microfone"
        for (rate in RATES) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val bufSize = Math.max(minBuf * 4, 30720)
            for (src in SOURCES) {
                val rec = try {
                    @Suppress("DEPRECATION")
                    AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                } catch (t: Throwable) { diag = "fonte $src@$rate: ${t.message}"; continue }
                if (rec.state != AudioRecord.STATE_INITIALIZED) { diag = "fonte $src@$rate: não inicializou"; rel(rec); continue }
                try { rec.startRecording() } catch (t: Throwable) { diag = "fonte $src@$rate: ${t.message}"; rel(rec); continue }
                // Mede o que realmente chega: estado, bytes lidos e o volume (pico) — distingue silêncio de sem-dados.
                val probe = ByteArray(1920)
                var total = 0; var maxAbs = 0; var reads = 0; var t = 0
                while (t < 40) {
                    val n = rec.read(probe, 0, probe.size)
                    if (n > 0) {
                        reads++; total += n
                        val sh = ByteBuffer.wrap(probe, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (sh.hasRemaining()) { val a = Math.abs(sh.get().toInt()); if (a > maxAbs) maxAbs = a }
                        if (total > 8000 && maxAbs > 30) break   // já temos áudio de verdade
                        if (reads > 8 && total > 20000) break    // bastante dado, decide pelo pico
                    } else if (n < 0 && n != AudioRecord.ERROR_INVALID_OPERATION) { break }
                    try { Thread.sleep(50) } catch (_: InterruptedException) {}
                    t++
                }
                val state = rec.recordingState
                if (total <= 0) { diag = "fonte $src@$rate: estado=$state, sem dados"; try { rec.stop() } catch (_: Throwable) {}; rel(rec); continue }
                if (maxAbs <= 30) { diag = "fonte $src@$rate: só silêncio (microfone bloqueado/mudo no sistema)"; try { rec.stop() } catch (_: Throwable) {}; rel(rec); continue }
                Log.i(TAG, "microfone ok fonte $src @ $rate (pico $maxAbs)")
                running = true
                val down = rate / 16000
                thread = Thread {
                    val inBuf = ByteArray(if (rate == 48000) 1920 else 640)
                    try {
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
        return diag
    }

    private fun sendDown(dc: DataChannel, data: ByteArray, len: Int, down: Int): Boolean {
        val inSh = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val nIn = inSh.remaining()
        if (down <= 1) {
            val out = ByteBuffer.wrap(data.copyOf(len))
            return try { dc.send(DataChannel.Buffer(out, true)); true } catch (_: Throwable) { false }
        }
        val out = ByteBuffer.allocate((nIn / down) * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + down <= nIn) {
            var acc = 0; for (k in 0 until down) acc += inSh.get(i + k).toInt()
            out.putShort((acc / down).toShort()); i += down
        }
        out.flip()
        return try { dc.send(DataChannel.Buffer(out, true)); true } catch (_: Throwable) { false }
    }

    private fun rel(rec: AudioRecord) { try { rec.release() } catch (_: Throwable) {} }

    @Synchronized
    fun stop() { running = false; thread = null }
}
