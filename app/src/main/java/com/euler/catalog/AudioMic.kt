package com.euler.catalog

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captura do microfone no próprio app (como o Electro, que funciona neste BYD), usando AudioRecord.Builder
 * — o construtor antigo deixava "estado=1, sem dados" aqui. 48 kHz, reduzido para 16 kHz, enviado pelo
 * canal de dados do WebRTC. Fonte padrão 7 (VOICE_COMMUNICATION), como o Electro, com alternativas.
 */
object AudioMic {
    private const val TAG = "UBYD-mic"
    private val SOURCES = intArrayOf(7, 6, 1, 5, 0) // VOICE_COMMUNICATION, VOICE_RECOGNITION, MIC, CAMCORDER, DEFAULT
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
            AdbShell.run(ctx, "appops set $pkg RECORD_AUDIO allow 2>/dev/null; cmd appops set $pkg RECORD_AUDIO allow 2>/dev/null; pm grant $pkg android.permission.RECORD_AUDIO 2>/dev/null; echo ok", 12000)
        } catch (_: Throwable) {}
    }

    private fun build(src: Int, rate: Int, buf: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(src)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build())
            .setBufferSizeInBytes(buf)
            .build()

    fun start(ctx: Context, dc: DataChannel): String {
        stop()
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prepared = false; prepare(ctx); return "liberei a permissão do microfone — toque em Ouvir de novo"
        }
        prepare(ctx)
        var diag = "sem microfone"
        for (rate in RATES) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val bufSize = Math.max(minBuf * 4, 30720)
            for (src in SOURCES) {
                val rec = try { build(src, rate, bufSize) } catch (t: Throwable) { diag = "fonte $src@$rate: ${t.message}"; continue }
                if (rec.state != AudioRecord.STATE_INITIALIZED) { diag = "fonte $src@$rate: não inicializou"; rel(rec); continue }
                try { rec.startRecording() } catch (t: Throwable) { diag = "fonte $src@$rate: ${t.message}"; rel(rec); continue }
                val probe = ByteArray(1920)
                var total = 0; var maxAbs = 0; var t = 0
                while (t < 40) {
                    val n = rec.read(probe, 0, probe.size)
                    if (n > 0) {
                        total += n
                        val sh = ByteBuffer.wrap(probe, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (sh.hasRemaining()) { val a = Math.abs(sh.get().toInt()); if (a > maxAbs) maxAbs = a }
                        if (total > 8000 && maxAbs > 30) break
                        if (total > 24000) break
                    } else if (n < 0 && n != AudioRecord.ERROR_INVALID_OPERATION) break
                    try { Thread.sleep(50) } catch (_: InterruptedException) {}
                    t++
                }
                val state = rec.recordingState
                if (total <= 0) { diag = "fonte $src@$rate: estado=$state, sem dados"; try { rec.stop() } catch (_: Throwable) {}; rel(rec); continue }
                if (maxAbs <= 30) { diag = "fonte $src@$rate: só silêncio (mic bloqueado no sistema)"; try { rec.stop() } catch (_: Throwable) {}; rel(rec); continue }
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
        if (down <= 1) return try { dc.send(DataChannel.Buffer(ByteBuffer.wrap(data.copyOf(len)), true)); true } catch (_: Throwable) { false }
        val inSh = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val nIn = inSh.remaining()
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

    fun stop() { running = false; thread = null }
}
