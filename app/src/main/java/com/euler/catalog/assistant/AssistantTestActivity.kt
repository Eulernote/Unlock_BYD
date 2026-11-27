package com.euler.catalog.assistant

import android.app.Activity
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
 * Tela de teste do assistente (passo intermediário). Permite DIGITAR um comando
 * em português e executá-lo de verdade no carro, antes da camada de voz existir.
 *
 * Objetivo: validar no Dolphin se CommandParser + AssistantExecutor realmente
 * acionam ar, vidros, apps e a chave remota — separando o risco do carro do
 * trabalho de reconhecimento de voz.
 *
 * Abrir pelo ícone "Hey Up (teste)" ou por ADB:
 *   am start -n com.euler.catalog/com.euler.catalog.assistant.AssistantTestActivity
 */
class AssistantTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val exec = AssistantExecutor(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0E0E0E"))
        }

        val titulo = TextView(this).apply {
            text = "Hey Up — teste de comandos"
            setTextColor(Color.WHITE); textSize = 18f
            setPadding(0, 0, 0, pad)
        }

        val entrada = EditText(this).apply {
            hint = "ex.: fecha os vidros, ar no 3, liga o carro"
            setHintTextColor(Color.parseColor("#888888"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val saida = TextView(this).apply {
            setTextColor(Color.parseColor("#CFCFCF")); textSize = 14f
            setPadding(0, pad, 0, 0)
            text = "Digite um comando e toque em Executar.\nO resultado e a acao aparecem aqui."
        }
        val scroll = ScrollView(this).apply {
            addView(saida)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val botao = Button(this).apply {
            text = "Executar"
            setOnClickListener {
                val frase = entrada.text.toString().trim()
                if (frase.isEmpty()) {
                    saida.text = "Escreva um comando primeiro."
                    return@setOnClickListener
                }
                val a = CommandParser.interpretar(frase)
                val detalhe = buildString {
                    append("Comando: ").append(frase).append("\n\n")
                    append("Tipo: ").append(a.type).append("\n")
                    append("Resposta: ").append(a.speak).append("\n")
                    a.pkg?.let { append("Pacote: ").append(it).append("\n") }
                    a.method?.let { append("Metodo: ").append(it).append(" ").append(a.args).append("\n") }
                    a.remoteCode?.let { append("Codigo chave remota: ").append(it).append("\n") }
                    a.destination?.let { append("Destino: ").append(it).append("\n") }
                    a.query?.let { append("Busca: ").append(it).append("\n") }
                    a.note?.let { append("Nota: ").append(it).append("\n") }
                }
                saida.text = detalhe + "\nExecutando..."
                // Executa fora da thread principal: powerOn dorme 2s e bydauto pode bloquear.
                Thread {
                    val falou = try { exec.execute(a) } catch (e: Throwable) { "Erro: ${e.message}" }
                    runOnUiThread { saida.text = detalhe + "\n>> " + falou }
                }.start()
            }
        }

        root.addView(titulo)
        root.addView(entrada)
        root.addView(botao)
        root.addView(scroll)
        setContentView(root)
    }
}
