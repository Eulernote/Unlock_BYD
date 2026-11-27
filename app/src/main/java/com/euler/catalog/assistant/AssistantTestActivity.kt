package com.euler.catalog.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela de teste do assistente. Dois modos no mesmo lugar:
 *  - TEXTO: digita o comando e executa (valida controle do carro sem voz).
 *  - VOZ: "Falar agora" (push-to-talk) e "Ativar voz" (modo ambiente, escuta "Hey Up").
 *
 * A resposta é falada pela voz nativa pt-BR (Razo entra depois). Os modelos de ouvir
 * são baixados do R2 na 1ª vez que a voz é usada.
 *
 * Abrir pelo ícone "Hey Up (teste)" ou por ADB:
 *   am start -n com.euler.catalog/com.euler.catalog.assistant.AssistantTestActivity
 */
class AssistantTestActivity : Activity() {

    private lateinit var exec: AssistantExecutor
    private lateinit var saida: TextView
    private var speaker: Speaker? = null
    private var engine: VoiceEngine? = null
    private var ambient = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exec = AssistantExecutor(this)
        speaker = Speaker(this)
        AppCatalog.init() // carrega o catálogo real da loja (nomes + pacotes) em segundo plano
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0E0E0E"))
        }

        val titulo = TextView(this).apply {
            text = "Hey Up — teste"; setTextColor(Color.WHITE); textSize = 18f
            setPadding(0, 0, 0, pad)
        }

        val entrada = EditText(this).apply {
            hint = "ex.: fecha os vidros, ar no 3, liga o carro"
            setHintTextColor(Color.parseColor("#888888")); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val btnTexto = Button(this).apply {
            text = "Executar (texto)"
            setOnClickListener {
                val frase = entrada.text.toString().trim()
                if (frase.isEmpty()) { saida.text = "Escreva um comando primeiro."; return@setOnClickListener }
                dispatch(frase)
            }
        }

        val linhaVoz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 2, 0, 0)
        }
        val btnFalar = Button(this).apply {
            text = "Falar agora"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { ensureMic { comModelos { voiceEngine().captureOnce() } } }
        }
        val btnAmbiente = Button(this).apply {
            text = "Ativar voz (Hey Up)"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (ambient) {
                    engine?.stop(); ambient = false; text = "Ativar voz (Hey Up)"
                    setStatus("Voz ambiente desligada.")
                } else {
                    ensureMic { comModelos {
                        voiceEngine().start(); ambient = true; text = "Desligar voz"
                        setStatus("Ouvindo… diga \"Hey Up\" e o comando.")
                    } }
                }
            }
        }
        linhaVoz.addView(btnFalar); linhaVoz.addView(btnAmbiente)

        saida = TextView(this).apply {
            setTextColor(Color.parseColor("#CFCFCF")); textSize = 14f
            setPadding(0, pad, 0, 0)
            text = "Digite um comando e toque em Executar, ou use a voz.\n" +
                "A 1ª vez que usar a voz, o app baixa os modelos do R2."
        }
        val scroll = ScrollView(this).apply {
            addView(saida)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        root.addView(titulo); root.addView(entrada); root.addView(btnTexto)
        root.addView(linhaVoz); root.addView(scroll)
        setContentView(root)
    }

    private fun voiceEngine(): VoiceEngine {
        engine?.let { return it }
        val e = VoiceEngine(
            ctx = this,
            onWake = { runOnUiThread { setStatus("Ouvindo o comando…") } },
            onText = { txt -> runOnUiThread { dispatch(txt) } },
            onError = { msg -> runOnUiThread { setStatus(msg) } }
        )
        engine = e
        return e
    }

    /** interpreta -> mostra -> executa (fundo) -> fala a resposta. */
    private fun dispatch(frase: String) {
        val a = CommandParser.interpretar(frase)
        val detalhe = buildString {
            append("Comando: ").append(frase).append("\n")
            append("Tipo: ").append(a.type).append("  |  ").append(a.speak)
            a.method?.let { append("\nMetodo: ").append(it).append(" ").append(a.args) }
            a.remoteCode?.let { append("\nCodigo: ").append(it) }
        }
        setStatus(detalhe + "\nExecutando…")
        Thread {
            val falou = try { exec.execute(a) } catch (e: Throwable) { "Erro: ${e.message}" }
            runOnUiThread { setStatus(detalhe + "\n>> " + falou) }
            speaker?.speak(falou)
        }.start()
    }

    private fun setStatus(s: String) { saida.text = s }

    // --- permissão de microfone ---
    private var pendingAfterMic: (() -> Unit)? = null
    private fun ensureMic(then: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            then()
        } else {
            pendingAfterMic = then
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 1001) {
            if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) pendingAfterMic?.invoke()
            else setStatus("Preciso da permissao de microfone pra usar a voz.")
            pendingAfterMic = null
        }
    }

    /** garante os modelos baixados (fundo), depois roda o bloco. */
    private fun comModelos(then: () -> Unit) {
        if (VoiceModels.isReady(this)) { then(); return }
        setStatus("Baixando modelos de voz do R2 (só na 1ª vez)…")
        Thread {
            val ok = VoiceModels.download(this) { msg -> runOnUiThread { setStatus(msg) } }
            runOnUiThread { if (ok) then() else setStatus("Nao consegui baixar os modelos de voz.") }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
        speaker?.shutdown()
    }
}
