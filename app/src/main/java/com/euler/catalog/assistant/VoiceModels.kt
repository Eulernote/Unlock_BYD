package com.euler.catalog.assistant

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Modelo de voz do "Hey Up" (Vosk, pt-BR).
 *
 * O modelo (vosk-model-small-pt-0.3) vem EMBUTIDO no APK em assets/model-pt (o CI baixa e
 * descompacta antes de compilar). Aqui a gente só copia de assets para o armazenamento interno
 * na 1ª vez (o Vosk precisa ler os arquivos por caminho de arquivo, não dá para ler de assets
 * comprimido). Não há download pela rede — funciona offline desde a 1ª vez.
 */
object VoiceModels {
    private const val ASSET_DIR = "model-pt"

    fun modelDir(ctx: Context): File = File(ctx.filesDir, "vosk-model-pt")

    /** true quando o modelo já está copiado para o armazenamento interno. */
    fun isReady(ctx: Context): Boolean {
        val d = modelDir(ctx)
        // os modelos pequenos do Vosk vêm com layout "plano" (final.mdl na raiz);
        // os grandes usam subpasta am/. Aceita os dois.
        return File(d, "final.mdl").exists() || File(d, "am/final.mdl").exists()
    }

    /**
     * Garante o modelo pronto. Mantém o nome "download" porque a tela de teste chama assim,
     * mas aqui é só cópia local de assets -> filesDir (rápido, sem rede).
     */
    fun download(ctx: Context, progress: (String) -> Unit): Boolean {
        if (isReady(ctx)) { progress("Modelo de voz pronto."); return true }
        return try {
            progress("Preparando o modelo de voz (1ª vez)...")
            CrashLog.stage(ctx, "voz: copiando modelo de assets")
            val dest = modelDir(ctx)
            // limpa resto de cópia incompleta
            if (dest.exists()) dest.deleteRecursively()
            copyAsset(ctx, ASSET_DIR, dest)
            val ok = isReady(ctx)
            progress(if (ok) "Modelo de voz pronto." else "Falha ao preparar o modelo.")
            CrashLog.stage(ctx, "voz: modelo copiado (ok=$ok)")
            ok
        } catch (e: Throwable) {
            CrashLog.write(ctx, "modelo", e)
            progress("Erro ao preparar o modelo: ${e.message}")
            false
        }
    }

    /** Copia recursivamente uma pasta (ou arquivo) de assets para o destino em filesDir. */
    private fun copyAsset(ctx: Context, assetPath: String, dest: File) {
        val am = ctx.assets
        val children = try { am.list(assetPath) } catch (_: Throwable) { null }
        if (children.isNullOrEmpty()) {
            // é arquivo
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input -> FileOutputStream(dest).use { out -> input.copyTo(out) } }
        } else {
            dest.mkdirs()
            for (c in children) copyAsset(ctx, "$assetPath/$c", File(dest, c))
        }
    }
}
