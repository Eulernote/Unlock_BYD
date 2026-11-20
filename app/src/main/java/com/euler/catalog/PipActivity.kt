package com.euler.catalog

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational

/**
 * "Tela limpa": entra em Picture-in-Picture pra enganar o launcher da BYD.
 * Quando existe uma janela PiP parada na tela, o próprio launcher esconde o dock (a barra de
 * ícones da home) depois de ~3s — foi o que o usuário observou com o YouTube em PiP. Então
 * mantemos uma janelinha PiP (quase invisível) na tela pra deixar o papel de parede limpo.
 * Fechar o PiP faz o dock voltar.
 */
class PipActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 26) { finish(); return }
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(1, 4))   // janelinha fina
                .build()
            @Suppress("DEPRECATION")
            enterPictureInPictureMode(params)
            // Volta pra home pra ver o papel de parede limpo (o PiP continua por cima).
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { finish() }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Quando o usuário fecha o PiP (X), encerramos — e o dock reaparece.
        if (!isInPictureInPictureMode) finish()
    }

    companion object {
        fun open(ctx: Context) {
            try {
                ctx.startActivity(Intent(ctx, PipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {}
        }
    }
}
