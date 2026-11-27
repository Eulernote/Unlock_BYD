package com.euler.catalog

import android.media.AudioFormat
import android.media.AudioRecord
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Diagnóstico do microfone no PROCESSO DO SHELL (uid 2000). Captura ~2 s em cada fonte e registra
 * se veio áudio (bytes) e o volume (pico), para sabermos se a captura privilegiada funciona onde a do
 * app falha. Escreve o resultado em /data/local/tmp/ubyd_mic.log.
 * Uso: app_process /system/bin com.euler.catalog.MicShell diag
 */
object MicShell {
    private val LOG = File("/data/local/tmp/ubyd_mic.log")
    private val SOURCES = intArrayOf(7, 6, 1, 5, 0)
    private fun log(s: String) { try { LOG.appendText(s + "\n") } catch (_: Throwable) {} }

    @JvmStatic
    fun main(args: Array<String>) {
        try { LOG.delete() } catch (_: Throwable) {}
        log("shell uid=${android.os.Process.myUid()}")
        val rate = 48000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = Math.max(minBuf * 4, 30720)
        for (src in SOURCES) {
            val rec = try {
                AudioRecord.Builder().setAudioSource(src)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(bufSize).build()
            } catch (t: Throwable) { log("fonte $src: criar ${t.message}"); continue }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { log("fonte $src: não inicializou"); rel(rec); continue }
            try { rec.startRecording() } catch (t: Throwable) { log("fonte $src: start ${t.message}"); rel(rec); continue }
            val buf = ByteArray(1920); var total = 0; var maxAbs = 0; var t = 0
            while (t < 40) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) { total += n; val sh = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(); while (sh.hasRemaining()) { val a = Math.abs(sh.get().toInt()); if (a > maxAbs) maxAbs = a }; if (total > 40000) break }
                else if (n < 0 && n != AudioRecord.ERROR_INVALID_OPERATION) { log("fonte $src: read=$n"); break }
                Thread.sleep(50); t++
            }
            log("fonte $src: estado=${rec.recordingState} bytes=$total pico=$maxAbs")
            try { rec.stop() } catch (_: Throwable) {}; rel(rec)
            if (total > 0 && maxAbs > 30) { log("RESULTADO: shell GRAVA na fonte $src (pico $maxAbs)"); return }
        }
        log("RESULTADO: nem o shell conseguiu gravar")
    }
    private fun rel(rec: AudioRecord) { try { rec.release() } catch (_: Throwable) {} }
}
