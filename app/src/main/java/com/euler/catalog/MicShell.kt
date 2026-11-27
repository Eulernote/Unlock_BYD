package com.euler.catalog

import android.media.AudioFormat
import android.media.AudioRecord
import java.io.File

/**
 * Captura do microfone rodando no PROCESSO DO SHELL (uid 2000), que tem privilégio de áudio que o app
 * comum (uid 10066) não tem — o AudioRecord do app fica em "estado=1, sem dados" neste BYD.
 * Lançado por app_process (CLASSPATH=apk). Grava PCM 48 kHz mono 16 bits e envia por um socket local
 * para o app (TCP 127.0.0.1), que reduz para 16 kHz e manda pelo canal de dados do WebRTC.
 * Uso: app_process /system/bin com.euler.catalog.MicShell <porta> <taxa>
 */
object MicShell {
    private val LOG = File("/data/local/tmp/ubyd_mic.log")
    private val SOURCES = intArrayOf(6, 1, 5, 7, 0)
    private fun log(s: String) { try { LOG.appendText(s + "\n") } catch (_: Throwable) {} }

    @JvmStatic
    fun main(args: Array<String>) {
        try { if (LOG.length() > 40_000) LOG.delete() } catch (_: Throwable) {}
        val port = args.getOrNull(0)?.toIntOrNull() ?: run { log("sem porta"); return }
        val rate = args.getOrNull(1)?.toIntOrNull() ?: 48000
        log("micshell iniciando (uid ${android.os.Process.myUid()}, $rate Hz)")
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { log("getMinBufferSize=$minBuf"); return }
        val bufSize = Math.max(minBuf * 4, 30720)
        var rec: AudioRecord? = null
        var usedSrc = -1
        for (src in SOURCES) {
            val r = try {
                @Suppress("DEPRECATION")
                AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            } catch (t: Throwable) { log("fonte $src: ${t.message}"); continue }
            if (r.state != AudioRecord.STATE_INITIALIZED) { log("fonte $src: não inicializou"); try { r.release() } catch (_: Throwable) {}; continue }
            try { r.startRecording() } catch (t: Throwable) { log("fonte $src: start ${t.message}"); try { r.release() } catch (_: Throwable) {}; continue }
            // espera engatar
            var ok = false
            for (i in 0 until 20) { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) { ok = true; break }; Thread.sleep(50) }
            if (!ok) { log("fonte $src: estado=${r.recordingState}, não engatou"); try { r.stop(); r.release() } catch (_: Throwable) {}; continue }
            rec = r; usedSrc = src; break
        }
        if (rec == null) { log("nenhuma fonte funcionou"); return }
        log("gravando na fonte $usedSrc")

        val sock = java.net.Socket()
        try { sock.connect(java.net.InetSocketAddress("127.0.0.1", port), 5000) }
        catch (t: Throwable) { log("tcp: ${t.message}"); try { rec.stop(); rec.release() } catch (_: Throwable) {}; return }
        val out = sock.getOutputStream()
        val buf = ByteArray(1920)
        var sent = 0L
        try {
            while (true) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { if (n == AudioRecord.ERROR_DEAD_OBJECT) break else continue }
                out.write(buf, 0, n); sent += n
            }
        } catch (t: Throwable) { log("fim: ${t.message} (enviado $sent)") }
        finally {
            try { rec.stop(); rec.release() } catch (_: Throwable) {}
            try { sock.close() } catch (_: Throwable) {}
            log("micshell encerrado (enviado $sent bytes)")
        }
    }
}
