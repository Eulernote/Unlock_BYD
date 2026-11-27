package com.euler.catalog.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela de teste do assistente.
 *
 * Importante: nesta multimídia, criar um EditText pode crashar (o Android tenta ler
 * DeviceConfig/TextClassifier e o provedor do sistema falha com RemoteException). Por isso
 * o teste por texto usa BOTÕES de comando prontos (não disparam esse bug). O campo de
 * digitar livre é opcional e criado de forma blindada; se falhar, a tela segue funcionando.
 *
 * Abrir pelo ícone "Hey Up (teste)" ou por ADB:
 *   am start -n com.euler.catalog/com.euler.catalog.assistant.AssistantTestActivity
 */
class AssistantTestActivity : Activity() {

    private lateinit var exec: AssistantExecutor
    private lateinit var saida: TextView
    private var entrada: EditText? = null
    private var speaker: Speaker? = null
    private var engine: VoiceEngine? = null
    private var ambient = false

    private val presets = listOf(
        "fecha os vidros", "abre os vidros",
        "liga o ar", "desliga o ar",
        "ar no 3", "temperatura 22",
        "liga o carro", "desliga o carro",
        "abre o YouTube", "toca Zeze di Camargo no Spotify",
        "abre o Waze pro Passeio das Aguas", "abre o radio",
        "abre as configuracoes", "abre o WhatsApp"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        exec = AssistantExecutor(this)
        speaker = Speaker(this)
        AppCatalog.init()

        val pad = (14 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = "Hey Up — teste"; setTextColor(Color.WHITE); textSize = 18f
            setPadding(0, 0, 0, pad / 2)
        })

        // Campo de digitar livre — opcional e blindado (pode crashar nesta ROM).
        try {
            val e = EditText(this).apply {
                hint = "ex.: fecha os vidros, ar no 3"
                setHintTextColor(Color.parseColor("#888888")); setTextColor(Color.WHITE)
                inputType = InputType.TYPE_CLASS_TEXT
            }
            if (Build.VERSION.SDK_INT >= 26) try { e.setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP) } catch (_: Throwable) {}
            entrada = e
            col.addView(e)
            col.addView(Button(this).apply {
                text = "Executar (texto digitado)"
                setOnClickListener { safe {
                    val f = entrada?.text?.toString()?.trim().orEmpty()
                    if (f.isEmpty()) setStatus("Escreva um comando primeiro.") else dispatch(f)
                } }
            })
        } catch (ex: Throwable) {
            CrashLog.write(applicationContext, "EditText", ex)
            col.addView(TextView(this).apply {
                text = "(campo de digitar indisponivel neste carro — use os botoes abaixo)"
                setTextColor(Color.parseColor("#E0A030")); textSize = 12f
            })
        }

        // Botões de comando prontos (caminho de texto confiável — não usa EditText).
        col.addView(TextView(this).apply {
            text = "Comandos rapidos:"; setTextColor(Color.parseColor("#AAAAAA")); textSize = 13f
            setPadding(0, pad, 0, 4)
        })
        var row: LinearLayout? = null
        presets.forEachIndexed { i, cmd ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row)
            }
            row!!.addView(Button(this).apply {
                text = cmd; textSize = 12f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { safe { dispatch(cmd) } }
            })
        }

        // Voz
        col.addView(TextView(this).apply {
            text = "Voz:"; setTextColor(Color.parseColor("#AAAAAA")); textSize = 13f
            setPadding(0, pad, 0, 4)
        })
        val voz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        voz.addView(Button(this).apply {
            text = "Falar agora"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { safe { ensureMic { comModelos { voiceEngine().captureOnce() } } } }
        })
        voz.addView(Button(this).apply {
            text = "Ativar voz (Hey Up)"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { safe {
                if (ambient) {
                    engine?.stop(); ambient = false; text = "Ativar voz (Hey Up)"; setStatus("Voz ambiente desligada.")
                } else ensureMic { comModelos {
                    voiceEngine().start(); ambient = true; text = "Desligar voz"
                    setStatus("Ouvindo… diga \"Hey Up\" e o comando.")
                } }
            } }
        })
        col.addView(voz)

        saida = TextView(this).apply {
            setTextColor(Color.parseColor("#CFCFCF")); textSize = 13f
            setPadding(0, pad, 0, 0)
            val stage = CrashLog.readStage(this@AssistantTestActivity)
            val crash = CrashLog.readLast(this@AssistantTestActivity)
            text = buildString {
                if (stage != null) append("ULTIMA ETAPA ALCANCADA:\n").append(stage).append("\n\n")
                if (crash != null) append("⚠ ULTIMO ERRO (toque um comando pra limpar):\n\n").append(crash)
                if (stage == null && crash == null) append("Toque um comando pronto, ou usa a voz.")
            }
        }
        col.addView(saida)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E0E0E"))
            addView(col)
        })
        // Sobrevivemos ao onCreate: limpa crash Java antigo (o da voz é nativo e vem pela ETAPA).
        CrashLog.clear(applicationContext)
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

    private fun dispatch(frase: String) {
        CrashLog.clear(applicationContext)
        val a = CommandParser.interpretar(frase)
        val detalhe = buildString {
            append("Comando: ").append(frase).append("\n")
            append("Tipo: ").append(a.type).append("  |  ").append(a.speak)
            a.pkg?.let { append("\nPacote: ").append(it) }
            a.method?.let { append("\nMetodo: ").append(it).append(" ").append(a.args) }
            a.remoteCode?.let { append("\nCodigo: ").append(it) }
        }
        setStatus(detalhe + "\nExecutando…")
        Thread {
            val falou = try { exec.execute(a) } catch (e: Throwable) {
                CrashLog.write(applicationContext, "execute", e); "Erro: ${e.message}"
            }
            runOnUiThread { setStatus(detalhe + "\n>> " + falou) }
            speaker?.speak(falou)
        }.start()
    }

    private fun setStatus(s: String) { saida.text = s }

    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            CrashLog.write(applicationContext, "UI", e)
            setStatus("Erro: ${e.message} (${e.javaClass.simpleName})")
        }
    }

    private var pendingAfterMic: (() -> Unit)? = null
    private fun ensureMic(then: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) then()
        else { pendingAfterMic = then; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001) }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 1001) {
            if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) safe { pendingAfterMic?.invoke() }
            else setStatus("Preciso da permissao de microfone pra usar a voz.")
            pendingAfterMic = null
        }
    }

    private fun comModelos(then: () -> Unit) {
        CrashLog.stage(applicationContext, "voz: verificando modelos")
        if (VoiceModels.isReady(this)) { then(); return }
        setStatus("Baixando modelos de voz do R2 (só na 1ª vez, ~100 MB)…")
        CrashLog.stage(applicationContext, "voz: baixando modelos do R2")
        Thread {
            try {
                val ok = VoiceModels.download(this) { msg -> runOnUiThread { setStatus(msg) } }
                CrashLog.stage(applicationContext, "voz: download terminou (ok=$ok)")
                runOnUiThread { if (ok) safe { then() } else setStatus("Nao consegui baixar os modelos de voz.") }
            } catch (e: Throwable) {
                CrashLog.write(applicationContext, "download", e)
                runOnUiThread { setStatus("Erro ao baixar: ${e.message}") }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
        speaker?.shutdown()
    }
}
