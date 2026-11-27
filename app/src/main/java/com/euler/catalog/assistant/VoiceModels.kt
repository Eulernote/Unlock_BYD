package com.euler.catalog.assistant

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Baixa os modelos de voz (ouvir) para o carro, a partir de um MANIFESTO no R2.
 *
 * O app não embute os modelos (decisão: baixar do R2 na 1ª vez). Em vez de fixar URLs
 * no código, ele lê um JSON de manifesto — assim dá pra trocar/ajustar os modelos sem
 * recompilar o app, só editando o arquivo no R2.
 *
 * Formato do manifesto (voice/manifest.json no R2):
 * {
 *   "files": [
 *     { "url": "https://.../silero_vad.onnx",        "path": "vad/silero_vad.onnx" },
 *     { "url": "https://.../kws-encoder.onnx",        "path": "kws/encoder.onnx" },
 *     { "url": "https://.../kws-decoder.onnx",        "path": "kws/decoder.onnx" },
 *     { "url": "https://.../kws-joiner.onnx",         "path": "kws/joiner.onnx" },
 *     { "url": "https://.../kws-tokens.txt",          "path": "kws/tokens.txt" },
 *     { "url": "https://.../whisper-encoder.onnx",    "path": "asr/encoder.onnx" },
 *     { "url": "https://.../whisper-decoder.onnx",    "path": "asr/decoder.onnx" },
 *     { "url": "https://.../whisper-tokens.txt",      "path": "asr/tokens.txt" }
 *   ]
 * }
 */
object VoiceModels {

    private const val TAG = "VoiceModels"

    // Base do worker (mesmo host do catálogo/APK). O manifesto fica em /f/voice/manifest.json.
    const val MANIFEST_URL =
        "https://euler-catalog.eulerfilhogontijodamata.workers.dev/f/voice/manifest.json"

    fun voiceDir(ctx: Context): File = File(ctx.filesDir, "voice").apply { mkdirs() }

    fun path(ctx: Context, rel: String): String = File(voiceDir(ctx), rel).absolutePath

    /** true se os arquivos essenciais já estão no disco (não precisa baixar de novo). */
    fun isReady(ctx: Context): Boolean {
        val d = voiceDir(ctx)
        val essenciais = listOf(
            "vad/silero_vad.onnx",
            "kws/encoder.onnx", "kws/decoder.onnx", "kws/joiner.onnx", "kws/tokens.txt", "kws/keywords.txt",
            "asr/encoder.onnx", "asr/decoder.onnx", "asr/tokens.txt"
        )
        return essenciais.all { File(d, it).exists() && File(d, it).length() > 0 }
    }

    /** Baixa tudo do manifesto. Chamar em thread de fundo. onProgress recebe texto de status. */
    fun download(ctx: Context, onProgress: (String) -> Unit): Boolean {
        return try {
            onProgress("Lendo manifesto…")
            val manifest = httpGet(MANIFEST_URL)
            val files = JSONObject(manifest).getJSONArray("files")
            for (i in 0 until files.length()) {
                val o = files.getJSONObject(i)
                val url = o.getString("url")
                val rel = o.getString("path")
                val dest = File(voiceDir(ctx), rel)
                if (dest.exists() && dest.length() > 0) continue
                onProgress("Baixando ${rel} (${i + 1}/${files.length()})…")
                dest.parentFile?.mkdirs()
                downloadFile(url, dest)
            }
            onProgress("Modelos prontos.")
            isReady(ctx)
        } catch (e: Throwable) {
            Log.w(TAG, "download falhou: ${e.message}")
            onProgress("Falha ao baixar modelos: ${e.message}")
            false
        }
    }

    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000
        }
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    private fun downloadFile(url: String, dest: File) {
        val tmp = File(dest.absolutePath + ".part")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 60000
        }
        c.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out, 1 shl 16) } }
        c.disconnect()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
    }
}
