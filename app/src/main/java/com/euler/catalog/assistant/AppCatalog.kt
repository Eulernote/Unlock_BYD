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
        live?.let { return it }
        // reserva + nativos
        val m = LinkedHashMap<String, String>()
        m.putAll(reserva); m.putAll(nativos)
        return m
    }

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
    fun findInText(texto: String): Pair<String, String>? {
        val m = mapa()
        // nomes mais longos primeiro, pra "youtube music" ganhar de "youtube"
        for (nome in m.keys.sortedByDescending { it.length }) {
            if (Regex("\\b" + Regex.escape(nome) + "\\b").containsMatchIn(texto)) {
                return nome to m.getValue(nome)
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
