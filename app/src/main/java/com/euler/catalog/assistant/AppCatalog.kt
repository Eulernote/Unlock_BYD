package com.euler.catalog.assistant

import com.euler.catalog.Store
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Nomes e pacotes dos apps que o assistente pode abrir.
 *
 * A fonte da verdade é o MESMO catálogo da loja Unlock BYD (/catalog.json no R2) —
 * então o pacote usado é sempre o real (ex.: o YouTube da loja é ReVanced,
 * app.revanced.android.youtube, não o do Google). Só reconhece o que está na loja;
 * o que o cliente instalar por fora não entra. Exceção: o Spotify, que é nativo do BYD.
 *
 * Carrega o catálogo em segundo plano e cacheia. Enquanto não carrega (ou offline),
 * usa uma lista mínima de reserva com os mesmos pacotes reais.
 */
object AppCatalog {

    // Apps nativos do próprio BYD (não vêm da loja), reconhecidos mesmo assim.
    private val nativos = linkedMapOf(
        "spotify" to "com.spotify.music"
    )

    // Reserva, caso o catálogo ainda não tenha carregado. Pacotes reais da loja.
    private val reserva = linkedMapOf(
        "youtube music" to "app.revanced.android.apps.youtube.music",
        "youtube" to "app.revanced.android.youtube",
        "waze" to "com.waze",
        "radarbot" to "com.vialsoft.radarbot_free",
        "velo play" to "com.global.veloplaytv",
        "cineverse" to "com.cineverse.app",
        "max net" to "com.exploudapps.maxnettv",
        "aurora store" to "com.aurora.store",
        "aurora" to "com.aurora.store"
    )

    @Volatile private var live: Map<String, String>? = null

    /** Dispara o carregamento do catálogo em segundo plano (chamar ao abrir a tela/serviço). */
    fun init() {
        if (live != null) return
        Thread({ carregar() }, "app-catalog").start()
    }

    private fun carregar() {
        try {
            val j = httpGet(Store.CATALOG_URL) ?: return
            val arr = JSONObject(j).optJSONArray("apps") ?: return
            val m = LinkedHashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val nome = o.optString("name").trim()
                val pkg = o.optString("package").trim()
                // ignora o instalador do Android (entrada com lock_url)
                if (nome.isNotEmpty() && pkg.isNotEmpty() && o.optString("lock_url").trim().isEmpty()) {
                    m[CommandParser.normalizar(nome)] = pkg
                }
            }
            nativos.forEach { (n, p) -> m[n] = p }
            if (m.isNotEmpty()) live = m
        } catch (e: Exception) { /* fica com a reserva */ }
    }

    private fun mapa(): Map<String, String> {
        // Sempre mescla a reserva + nativos como base, e deixa o catálogo da loja por cima.
        // Assim os nomes falados comuns (waze, youtube, spotify) resolvem mesmo que a loja
        // nomeie o app de outro jeito; e o catálogo da loja tem prioridade quando existe.
        val m = LinkedHashMap<String, String>()
        m.putAll(reserva); m.putAll(nativos)
        live?.let { m.putAll(it) }
        return m
    }

    /** Todos os nomes de apps conhecidos (para alimentar a gramática do reconhecimento de voz). */
    fun nomes(): Set<String> = try { mapa().keys } catch (_: Throwable) { emptySet() }

    /** Resolve um nome falado (ex.: "youtube") para o pacote real, ou null. */
    fun resolve(nome: String): String? {
        val n = CommandParser.normalizar(nome)
        val m = mapa()
        m[n]?.let { return it }
        // tenta por conter
        for ((k, v) in m) if (n == k) return v
        for ((k, v) in m) if (n.contains(k) || k.contains(n)) return v
        return null
    }

    /** Acha, dentro do texto do comando, o nome de app mais específico presente. */
    // Sinônimos/categorias: palavra falada -> um "token" que costuma aparecer no nome do app.
    private val sinonimos = linkedMapOf(
        "equalizador" to "equaliz", "equalizacao" to "equaliz", "equalize" to "equaliz",
        "jogos" to "emulad", "jogo" to "emulad", "games" to "emulad", "game" to "emulad",
        "emulador" to "emulad", "emuladores" to "emulad", "emulators" to "emulad",
        "radar" to "radar", "radares" to "radar",
        "navegador" to "waze", "navegacao" to "waze", "gps" to "waze",
        "yt" to "youtube", "video" to "youtube", "videos" to "youtube",
        "loja" to "aurora", "lojinha" to "aurora"
    )
    // Palavras genéricas que NÃO devem casar sozinhas (evita falso positivo).
    private val genericas = setOf(
        "o","a","os","as","de","do","da","no","na","em","store","play","app","apps","tv",
        "pro","free","mod","net","go","lite","plus","br","oficial","central","home","the","and","com"
    )

    private fun palavrasDoNome(nome: String) =
        nome.split(" ", "-", "_", ".", "+").map { it.trim() }.filter { it.length >= 3 }

    private fun levenshtein(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    /** Vocabulário (nomes + palavras dos nomes + sinônimos) para alimentar a gramática do Vosk. */
    fun vocabulario(): Set<String> {
        val s = linkedSetOf<String>()
        try { mapa().keys.forEach { n -> s.add(n); palavrasDoNome(n).forEach { if (it !in genericas) s.add(it) } } } catch (_: Throwable) {}
        s.addAll(sinonimos.keys)
        return s
    }

    /**
     * Acha o app citado na frase, tolerante a variações:
     * 1) nome completo ("youtube music"); 2) palavra do nome ("aurora" -> Aurora Store,
     * "sapo" -> Sapo Central); 3) sinônimo/categoria ("equalizador", "jogos", "vídeo");
     * 4) aproximação por pronúncia (Levenshtein, p/ "radarbot" etc.).
     */
    fun findInText(texto: String): Pair<String, String>? {
        val m = mapa()
        val t = CommandParser.normalizar(texto)
        val palavras = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        // 1) nome completo como frase (mais preciso), nomes mais longos primeiro
        for (nome in m.keys.sortedByDescending { it.length }) {
            if (Regex("\\b" + Regex.escape(nome) + "\\b").containsMatchIn(t)) return nome to m.getValue(nome)
        }
        // 2) palavra significativa do nome aparece na fala (pega a palavra casada mais longa)
        var melhor: Pair<String, String>? = null; var melhorLen = 0
        for ((nome, pkg) in m) for (w in palavrasDoNome(nome)) {
            if (w in genericas) continue
            if (palavras.any { it == w } && w.length > melhorLen) { melhor = nome to pkg; melhorLen = w.length }
        }
        if (melhor != null) return melhor
        // 3) sinônimos: palavra falada -> token que deve estar no nome de algum app
        for (p in palavras) sinonimos[p]?.let { token ->
            for ((nome, pkg) in m) if (nome.contains(token)) return nome to pkg
        }
        // 4) aproximação por pronúncia (palavras com 5+ letras, distância <= 2)
        for (p in palavras) {
            if (p.length < 5 || p in genericas) continue
            for ((nome, pkg) in m) for (w in palavrasDoNome(nome)) {
                if (w.length >= 5 && levenshtein(p, w) <= 2) return nome to pkg
            }
        }
        return null
    }

    /**
     * Resolve um app NATIVO do BYD pelo nome visível no carro (o pacote varia por modelo).
     * Ex.: "radio" acha o app cujo rótulo contém "radio"; "configurac" acha "Configurações".
     * Precisa do Context (roda no executor). Prefere pacotes do sistema/BYD.
     */
    fun resolveByLabel(ctx: android.content.Context, termo: String): String? {
        return try {
            val pm = ctx.packageManager
            val n = CommandParser.normalizar(termo)
            var fallback: String? = null
            for (ai in pm.getInstalledApplications(0)) {
                val label = try { CommandParser.normalizar(pm.getApplicationLabel(ai).toString()) } catch (e: Exception) { "" }
                if (label.isNotEmpty() && label.contains(n)) {
                    val p = ai.packageName
                    // prefere apps do sistema/BYD (rádio e configurações nativos)
                    if (p.startsWith("com.byd") || p.startsWith("com.android") || p.contains("radio") || p.contains("setting"))
                        return p
                    if (fallback == null) fallback = p
                }
            }
            fallback
        } catch (e: Exception) { null }
    }

    private fun httpGet(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 12000
        }
        c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    } catch (e: Exception) { null }
}
