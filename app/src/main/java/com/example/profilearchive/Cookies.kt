package com.example.profilearchive

import android.content.Context
import java.io.File

/**
 * Cookies de login, um arquivo por site (filesDir/cookies/<site>.txt) no formato Netscape (cookies.txt).
 *
 * Por que converter: passada como cabeçalho "Cookie:", o yt-dlp prende os cookies só ao domínio exato
 * do link (www.tiktok.com). As requisições para a API e para a CDN de vídeo (outros subdomínios)
 * saíam sem login. No cookies.txt eles ficam em ".tiktok.com" e valem para todos os subdomínios.
 */
object CookieStore {
    val SITES = linkedMapOf("tiktok" to "TikTok", "instagram" to "Instagram", "x" to "X", "youtube" to "YouTube", "reddit" to "Reddit")

    private val DOMAIN = mapOf("tiktok" to ".tiktok.com", "instagram" to ".instagram.com", "x" to ".x.com", "youtube" to ".youtube.com", "reddit" to ".reddit.com")

    /** Cookies típicos de cada site: identificam de onde veio a linha "a=b; c=d" colada. */
    private val SIGNATURE = mapOf(
        "tiktok" to setOf("ttwid", "tt_chain_token", "tt_csrf_token", "msToken", "sid_guard", "odin_tt"),
        "instagram" to setOf("ds_user_id", "ig_did", "mid", "rur", "shbid", "csrftoken"),
        "x" to setOf("auth_token", "ct0", "twid", "guest_id", "kdt"),
        "reddit" to setOf("reddit_session", "token_v2", "loid", "edgebucket", "csv", "session_tracker"),
        "youtube" to setOf("SAPISID", "__Secure-3PSID", "LOGIN_INFO", "VISITOR_INFO1_LIVE", "HSID", "SID"),
    )

    /** Cookie que só existe com conta logada. */
    private val LOGIN = mapOf("tiktok" to "sessionid", "instagram" to "sessionid", "x" to "auth_token", "youtube" to "SAPISID", "reddit" to "reddit_session")

    private const val YEAR_S = 365L * 24 * 3600

    data class Cookie(val domain: String, val name: String, val value: String, val expiry: Long)

    private fun dir(ctx: Context) = File(ctx.filesDir, "cookies").apply { mkdirs() }
    fun file(ctx: Context, site: String) = File(dir(ctx), "$site.txt")

    fun siteOfDomain(d: String): String? = d.lowercase().let {
        when {
            "tiktok" in it -> "tiktok"
            "instagram" in it -> "instagram"
            "twitter" in it || it.trimStart('.') == "x.com" || it.endsWith(".x.com") -> "x"
            "youtube" in it || "google" in it -> "youtube"
            "reddit" in it -> "reddit"
            else -> null
        }
    }

    fun detect(names: Collection<String>): String? =
        SIGNATURE.maxByOrNull { (_, sig) -> names.count { it in sig } }
            ?.takeIf { (_, sig) -> names.any { it in sig } }?.key

    /**
     * Lê tudo o que estiver no campo, misturando formatos e sites à vontade:
     * - linhas de cookies.txt (Netscape): o site vem do domínio de cada linha;
     * - linhas "a=b; c=d" (cabeçalho Cookie): cada linha é um bloco e o site é detectado pelos nomes;
     *   linha que não dá para identificar herda o site da linha anterior.
     * @return cookies por site (o último de mesmo nome vence) e quantas linhas foram ignoradas
     */
    private fun parse(text: String): Pair<Map<String, List<Cookie>>, Int> {
        val now = System.currentTimeMillis() / 1000
        val out = LinkedHashMap<String, LinkedHashMap<String, Cookie>>()
        var ignored = 0
        var lastSite: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || (line.startsWith("#") && !line.startsWith("#HttpOnly_"))) continue
            val f = line.removePrefix("#HttpOnly_").split('\t')
            if (f.size >= 7) {                                   // Netscape
                val site = siteOfDomain(f[0]) ?: run { ignored++; null } ?: continue
                out.getOrPut(site) { LinkedHashMap() }[f[5]] =
                    Cookie(f[0], f[5], f[6].trim(), f[4].toLongOrNull()?.takeIf { it > 0 } ?: (now + YEAR_S))
                lastSite = site
                continue
            }
            val pairs = line.replace(Regex("^cookie:\\s*", RegexOption.IGNORE_CASE), "")
                .split(';').map { it.trim() }.filter { '=' in it }
                .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                .filter { it.first.isNotEmpty() && it.first.none { c -> c.isWhitespace() } }
            if (pairs.isEmpty()) { ignored++; continue }
            val site = detect(pairs.map { it.first }) ?: lastSite ?: run { ignored++; null } ?: continue
            val bucket = out.getOrPut(site) { LinkedHashMap() }
            pairs.forEach { (n, v) -> bucket[n] = Cookie(DOMAIN.getValue(site), n, v, now + YEAR_S) }
            lastSite = site
        }
        return out.mapValues { it.value.values.toList() } to ignored
    }

    private fun read(f: File): List<Cookie> = if (!f.isFile) emptyList() else
        f.readLines().filter { !it.startsWith("#") || it.startsWith("#HttpOnly_") }
            .mapNotNull { l -> l.removePrefix("#HttpOnly_").split('\t').takeIf { it.size >= 7 } }
            .map { Cookie(it[0], it[5], it[6], it[4].toLongOrNull() ?: 0) }

    private fun netscapeLines(cookies: List<Cookie>) = cookies.joinToString("\n") { c ->
        "${c.domain}\tTRUE\t/\tTRUE\t${c.expiry}\t${c.name}\t${c.value.replace(Regex("[\\t\\r\\n]"), "")}"
    }

    /** Tudo o que está guardado, como texto (cookies.txt) para mostrar e editar no campo. */
    fun asText(ctx: Context): String {
        migrateOld(ctx)
        val all = SITES.keys.flatMap { read(file(ctx, it)) }
        return if (all.isEmpty()) "" else "# Netscape HTTP Cookie File\n" + netscapeLines(all)
    }

    /**
     * O campo passa a ser a lista completa: salva o que estiver nele e apaga os sites que saíram.
     * Para adicionar cookies de outro site é só colar embaixo dos que já estão. Retorna a mensagem.
     */
    fun replaceAll(ctx: Context, text: String): String {
        val (parsed, ignored) = parse(text)
        SITES.keys.forEach { site ->
            val list = parsed[site]
            if (list.isNullOrEmpty()) file(ctx, site).delete()
            else file(ctx, site).writeText("# Netscape HTTP Cookie File\n" + netscapeLines(list) + "\n")
        }
        val sites = parsed.entries.joinToString(" · ") { (s, c) ->
            "${SITES[s]}: ${c.size}" + if (c.any { it.name == LOGIN[s] }) " (logado)" else " (sem login)"
        }
        return when {
            parsed.isEmpty() && ignored == 0 -> "Cookies removidos"
            parsed.isEmpty() -> "Não identifiquei de qual site são esses cookies"
            ignored > 0 -> "Salvo — $sites · $ignored linha(s) ignorada(s)"
            else -> "Salvo — $sites"
        }
    }

    /** Cookies guardados de um site (ex.: para entregar à WebView do Instagram). */
    fun cookiesOf(ctx: Context, site: String): List<Cookie> { migrateOld(ctx); return read(file(ctx, site)) }

    fun hasLogin(ctx: Context, site: String) = read(file(ctx, site)).any { it.name == LOGIN[site] }

    private fun asTextUnchecked(ctx: Context) = netscapeLines(SITES.keys.flatMap { read(file(ctx, it)) })

    /** Versões anteriores guardavam tudo num único filesDir/cookies.txt. */
    fun migrateOld(ctx: Context) {
        val old = File(ctx.filesDir, "cookies.txt")
        if (!old.isFile) return
        runCatching {
            val merged = asTextUnchecked(ctx) + "\n" + old.readText()
            replaceAll(ctx, merged)
        }
        old.delete()
    }
}

/**
 * Cookies do site de um download. Cada processo do yt-dlp recebe uma cópia própria, porque o
 * yt-dlp regrava o arquivo ao terminar e vários processos gravando o mesmo arquivo o corromperiam.
 */
class SiteCookies(ctx: Context, site: String, private val tag: String) {
    private val src = CookieStore.run { migrateOld(ctx); file(ctx, site) }
    private val tmp = ctx.cacheDir
    val loggedIn = CookieStore.hasLogin(ctx, site)

    fun forProcess(procId: String): File? =
        src.takeIf { it.isFile }?.let { s -> File(tmp, "ck-$tag-$procId.txt").also { s.copyTo(it, overwrite = true) } }

    fun cleanup() { tmp.listFiles { f -> f.name.startsWith("ck-$tag-") }?.forEach { it.delete() } }
}
