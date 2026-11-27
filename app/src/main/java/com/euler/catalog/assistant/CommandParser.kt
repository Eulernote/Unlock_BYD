package com.euler.catalog.assistant

import java.text.Normalizer

/**
 * Interpretador de comandos de voz (pt-BR) do assistente "Hey Up".
 *
 * Recebe a frase JÁ TRANSCRITA pelo reconhecimento de voz e devolve a ação a executar.
 * Não precisa de internet nem de modelo de IA: é um casador de frases por regras.
 *
 * Portado do protótipo Python validado (20/21 frases de teste). O executor
 * (AssistantExecutor) é quem transforma cada Action no comando real do carro:
 *   OPEN_APP   -> abre app por pacote (startActivity / launchIntent)
 *   WAZE       -> intent waze://?q=...&navigate=yes
 *   MEDIA      -> busca+play no Spotify / busca no YouTube
 *   AC         -> API android.hardware.bydauto.ac.BYDAutoAcDevice (via AdbShell/reflection)
 *   WINDOWS    -> bodywork.setAllWindowState(df,pf,dt,pt)
 *   REMOTE_KEY -> chave remota setBuffer(1034,-1442840572,frame) byte[22]=code
 *   INFO       -> resposta falada, sem ação no carro
 *   UNKNOWN    -> não entendeu; pede pra repetir
 */

enum class ActionType { OPEN_APP, WAZE, MEDIA, AC, WINDOWS, REMOTE_KEY, INFO, UNKNOWN }

data class Action(
    val type: ActionType,
    val speak: String,                 // o que o assistente fala de volta
    val pkg: String? = null,           // pacote do app alvo
    val method: String? = null,        // método bydauto (ac/janela)
    val args: List<Int> = emptyList(), // argumentos do método
    val remoteCode: Int? = null,       // código da chave remota
    val query: String? = null,         // busca de mídia
    val destination: String? = null,   // destino do Waze
    val note: String? = null           // observação técnica (ex.: sequência power-on)
)

object CommandParser {

    /** Catálogo mínimo; no app real vem da biblioteca de apps do painel. */
    val catalogo: Map<String, String> = linkedMapOf(
        "youtube music" to "com.google.android.apps.youtube.music",
        "youtube" to "com.google.android.youtube",
        "waze" to "com.waze",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "whatsapp" to "com.whatsapp",
        "radarbot" to "com.vialsoft.radarbot_free",
        "veloplay" to "com.veloplay.app"
    )

    val remoteKey = mapOf(
        "ligar_carro" to 1, "desligar_carro" to 9,
        "travar" to 7, "destravar" to 5, "porta_malas" to 10, "pisca" to 22
    )

    private val extenso = linkedMapOf(
        "vinte e um" to 21, "vinte e dois" to 22, "vinte e tres" to 23, "vinte e quatro" to 24,
        "vinte e cinco" to 25, "vinte e seis" to 26, "vinte e sete" to 27, "vinte e oito" to 28,
        "vinte e nove" to 29, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18,
        "dezenove" to 19, "vinte" to 20, "trinta" to 30, "um" to 1, "dois" to 2, "tres" to 3,
        "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7
    )

    fun normalizar(texto: String): String {
        var t = texto.lowercase().trim()
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t = t.replace(Regex("[^\\w\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        return t
    }

    private fun numero(t: String): Int? {
        Regex("\\b(\\d{1,2})\\b").find(t)?.let { return it.groupValues[1].toInt() }
        for ((ext, n) in extenso.entries.sortedByDescending { it.key.length }) {
            if (Regex("\\b" + ext.replace(" ", "\\s+") + "\\b").containsMatchIn(t)) return n
        }
        return null
    }

    private fun acharApp(t: String): Pair<String, String>? {
        for (nome in catalogo.keys.sortedByDescending { it.length }) {
            if (Regex("\\b" + Regex.escape(nome) + "\\b").containsMatchIn(t)) return nome to catalogo.getValue(nome)
        }
        return null
    }

    private fun depoisDe(t: String, gatilhos: List<String>): String? {
        for (g in gatilhos) {
            Regex("$g\\s+(.*)$").find(t)?.let {
                var alvo = it.groupValues[1].trim()
                alvo = alvo.replace(Regex("\\b(por favor|pra mim|para mim|ai|agora|no carro)\\b"), "").trim()
                if (alvo.isNotEmpty()) return alvo
            }
        }
        return null
    }

    private fun limparQuery(bruto: String): String {
        var a = bruto
        a = a.replace(Regex("\\b(no|na|pelo|pela)\\s+(spotify|youtube( music)?)\\b"), "")
        a = a.replace(Regex("\\b(spotify|youtube( music)?)\\b"), "")
        a = a.replace(Regex("^\\s*(e\\s+)?(coloca|coloque|toca|tocar|procura|pesquisa|poe|bota|com)\\b"), "").trim()
        a = a.replace(Regex("^(?:(?:um|uma|uns|umas|o|a)\\s+)?(?:videos?|musica|cancao|playlist|som)\\b(?:\\s+d[eoa]s?)?\\b"), "").trim()
        a = a.replace(Regex("^(do|da|de|o|a|um|uma)\\s+"), "").trim()
        return a
    }

    fun interpretar(frase: String): Action {
        var t = normalizar(frase)
        t = t.replace(Regex("^(hey up|ei up|e up|ok up|up)\\b"), "").trim()

        // 1. Ligar/desligar veículo
        if (Regex("\\b(liga|ligar|ligue)\\b.*\\bcarro\\b|\\bda\\s+partida\\b").containsMatchIn(t))
            return Action(ActionType.REMOTE_KEY, "Ligando o carro.", remoteCode = remoteKey["ligar_carro"],
                note = "mandar 9, esperar 2s, mandar 1")
        if (Regex("\\b(desliga|desligar|desligue)\\b.*\\bcarro\\b").containsMatchIn(t))
            return Action(ActionType.REMOTE_KEY, "Desligando o carro.", remoteCode = remoteKey["desligar_carro"])

        // 2. Travas / porta-malas
        if (Regex("\\bdestrav").containsMatchIn(t))
            return Action(ActionType.REMOTE_KEY, "Destravando.", remoteCode = remoteKey["destravar"])
        if (Regex("\\btrav").containsMatchIn(t))
            return Action(ActionType.REMOTE_KEY, "Travando.", remoteCode = remoteKey["travar"])
        if (Regex("\\bporta\\s*mala").containsMatchIn(t))
            return Action(ActionType.REMOTE_KEY, "Abrindo o porta-malas.", remoteCode = remoteKey["porta_malas"])

        // 3. Vidros
        if (Regex("\\bvidro|\\bjanela").containsMatchIn(t)) {
            val soMotorista = Regex("motorista").containsMatchIn(t)
            if (Regex("\\bfecha").containsMatchIn(t)) {
                val est = if (soMotorista) listOf(2, 1, 1, 1) else listOf(2, 2, 2, 2)
                return Action(ActionType.WINDOWS, "Fechando os vidros.", method = "setAllWindowState", args = est)
            }
            if (Regex("\\bventila|\\bentreabre").containsMatchIn(t))
                return Action(ActionType.WINDOWS, "Ventilando os vidros.", method = "setAllWindowState(ventilar)")
            if (Regex("\\babre|\\babrir|\\babra|\\bbaixa|\\bbaixar").containsMatchIn(t)) {
                val est = if (soMotorista) listOf(1, 2, 2, 2) else listOf(1, 1, 1, 1)
                val fala = if (soMotorista) "Abrindo o vidro do motorista." else "Abrindo os vidros."
                return Action(ActionType.WINDOWS, fala, method = "setAllWindowState", args = est)
            }
        }

        // 4. Ar-condicionado
        if (Regex("\\bar\\b|ar condicionado|climatiz|temperatura|ventila").containsMatchIn(t)) {
            if (Regex("\\b(desliga|desligar|desligue)\\b").containsMatchIn(t))
                return Action(ActionType.AC, "Desligando o ar.", method = "stop")
            if (Regex("temperatura|graus?").containsMatchIn(t)) {
                numero(t)?.let { n ->
                    val c = n.coerceIn(16, 33)
                    return Action(ActionType.AC, "Temperatura em $c graus.", method = "setAcTemperature", args = listOf(0, c))
                }
            }
            if (Regex("\\bno\\b|\\bnivel|ventila").containsMatchIn(t)) {
                numero(t)?.let { n ->
                    if (n in 1..7) return Action(ActionType.AC, "Ventilacao no $n.", method = "setAcWindLevel", args = listOf(0, n))
                }
            }
            if (Regex("\\b(liga|ligar|ligue)\\b").containsMatchIn(t))
                return Action(ActionType.AC, "Ar ligado.", method = "start")
        }

        // 5. Waze
        if (t.contains("waze") || Regex("\\b(navega|rota|como chego|me leva)\\b").containsMatchIn(t)) {
            val dest = depoisDe(t, listOf(
                "waze (?:pro|para o|pra|para|ate o|ate a|ate|no|na|em)",
                "(?:navega|rota|me leva|como chego)\\s*(?:pro|para o|pra|para|ate o|ate a|ate|no|na|em)?"
            ))
            if (dest != null)
                return Action(ActionType.WAZE, "Tracando rota pro $dest.", pkg = "com.waze", destination = dest)
        }

        // 6. Mídia (Spotify / YouTube)
        val app = acharApp(t)
        val ehServico = app?.first in listOf("spotify", "youtube", "youtube music")
        if (ehServico || Regex("\\b(toca|tocar|coloca uma musica|poe uma musica)\\b").containsMatchIn(t)) {
            val bruto = depoisDe(t, listOf("(?:toca|tocar|coloca|colocar|poe|bota|procura|pesquisa)", "(?:youtube|spotify)"))
            if (bruto != null) {
                val q = limparQuery(bruto)
                if (q.isNotEmpty()) {
                    val servico = when {
                        app?.first?.startsWith("youtube") == true -> "youtube"
                        app?.first == "spotify" -> "spotify"
                        t.contains("youtube") -> "youtube"
                        else -> "spotify"
                    }
                    return if (servico == "youtube")
                        Action(ActionType.MEDIA, "Abrindo o YouTube com $q.", pkg = catalogo["youtube"], query = q, note = "busca YouTube")
                    else
                        Action(ActionType.MEDIA, "Tocando $q no Spotify.", pkg = catalogo["spotify"], query = q, note = "Spotify search+play")
                }
            }
        }

        // 7. Câmeras (função interna do app)
        if (Regex("\\bcamera").containsMatchIn(t))
            return Action(ActionType.OPEN_APP, "Abrindo as cameras.", note = "funcao interna Unlock/Update Lab")

        // 8. Trânsito (resposta por suposição)
        if (Regex("transito|movimentad|congestion").containsMatchIn(t)) {
            val cidade = depoisDe(t, listOf("(?:em|no|na|de)")) ?: "a cidade"
            return Action(ActionType.INFO,
                "Costuma ter transito carregado nas avenidas principais de $cidade neste horario. Quer que eu abra o Waze?")
        }

        // 9. Abrir app
        if (Regex("\\b(abre|abrir|abra|inicia|chama)\\b").containsMatchIn(t)) {
            acharApp(t)?.let { (nome, pkg) ->
                return Action(ActionType.OPEN_APP, "Abrindo o $nome.", pkg = pkg)
            }
        }

        return Action(ActionType.UNKNOWN, "Nao entendi. Pode repetir?")
    }
}
