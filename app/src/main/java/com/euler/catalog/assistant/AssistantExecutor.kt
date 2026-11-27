package com.euler.catalog.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Executa a Action devolvida pelo CommandParser: abre apps, dispara intents de
 * Waze/Spotify/YouTube e aciona o carro via BydControl. Devolve a frase a ser
 * falada pela voz (Razo) — quem fala é a camada de voz (AssistantService).
 *
 * As partes de carro (ar/vidros/power) rodam em thread de fundo pelo chamador,
 * porque powerOn dorme 2s e as chamadas bydauto podem bloquear.
 */
class AssistantExecutor(private val ctx: Context) {

    private val TAG = "AssistantExec"

    /** Executa e devolve o texto de resposta falada. */
    fun execute(a: Action): String {
        try {
            when (a.type) {
                ActionType.OPEN_APP -> a.pkg?.let { openApp(it) }
                ActionType.WAZE -> a.destination?.let { openWaze(it) }
                ActionType.MEDIA -> a.query?.let { openMedia(a.pkg, it) }
                ActionType.AC -> runAc(a)
                ActionType.WINDOWS -> runWindows(a)
                ActionType.REMOTE_KEY -> runRemote(a)
                ActionType.INFO -> {}
                ActionType.UNKNOWN -> {}
            }
        } catch (e: Throwable) {
            Log.w(TAG, "execute ${a.type} falhou: ${e.message}")
            return "Nao consegui fazer isso agora."
        }
        return a.speak
    }

    private fun openApp(pkg: String) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
        } else {
            Log.w(TAG, "app nao instalado: $pkg")
        }
    }

    private fun openWaze(dest: String) {
        val uri = Uri.parse("https://waze.com/ul?q=" + Uri.encode(dest) + "&navigate=yes")
        val i = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.waze")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { ctx.startActivity(i) } catch (e: Exception) {
            // Waze nao instalado -> abre no navegador/loja
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openMedia(pkg: String?, query: String) {
        if (pkg != null && pkg.contains("spotify")) {
            // Spotify: busca via intent de SEARCH
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))).apply {
                setPackage("com.spotify.music"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { ctx.startActivity(i); return } catch (_: Exception) {}
        }
        // YouTube (ou fallback): busca — pacote da loja é o ReVanced
        val yt = Intent(Intent.ACTION_SEARCH).apply {
            `package` = pkg ?: "app.revanced.android.youtube"
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { ctx.startActivity(yt) } catch (e: Exception) {
            val web = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(web)
        }
    }

    private fun runAc(a: Action) {
        when (a.method) {
            "start" -> BydControl.acStart(ctx)
            "stop" -> BydControl.acStop(ctx)
            "setAcTemperature" -> if (a.args.size >= 2) BydControl.acTemperature(ctx, a.args[0], a.args[1])
            "setAcWindLevel" -> if (a.args.size >= 2) BydControl.acWindLevel(ctx, a.args[1])
        }
    }

    private fun runWindows(a: Action) {
        if (a.method == "setAllWindowState" && a.args.size == 4) {
            BydControl.setWindows(ctx, a.args[0], a.args[1], a.args[2], a.args[3])
        }
        // "setAllWindowState(ventilar)" entra quando confirmarmos o valor de ventilacao por modelo
    }

    private fun runRemote(a: Action) {
        when (a.remoteCode) {
            1 -> BydControl.powerOn(ctx)   // usa a sequencia 9 -> 2s -> 1
            9 -> BydControl.powerOff(ctx)
            else -> a.remoteCode?.let { BydControl.remoteKey(ctx, it) }
        }
    }
}
