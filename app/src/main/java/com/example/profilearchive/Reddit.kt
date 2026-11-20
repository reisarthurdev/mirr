package com.example.profilearchive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reddit pela API JSON pública (o mesmo ".json" que o site usa), sem o yt-dlp na listagem:
 * o yt-dlp só sabe abrir um post por vez, não lista usuário nem subreddit.
 *
 * Perfil no app: "u_<nome>" (usuário) ou "r_<nome>" (subreddit), igual ao jeito que o próprio Reddit
 * nomeia o subreddit de perfil dos usuários. Só posts com mídia hospedada no Reddit/Imgur entram:
 *   - fotos e galerias viram "slides" (o app baixa direto, como no Instagram)
 *   - vídeos v.redd.it vão pelo yt-dlp (precisa juntar vídeo + áudio, que o Reddit serve separados)
 * Os comentários de cada post ficam em .dados/<id>.comments.json.
 */
object Reddit {

    class Failed(msg: String) : Exception(msg)

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private val IMAGE_EXT = Regex("\\.(jpe?g|png|gif|webp)(\\?.*)?$", RegexOption.IGNORE_CASE)

    fun isSubreddit(username: String) = username.startsWith("r_")
    fun nameOf(username: String) = username.removePrefix("r_").removePrefix("u_")
    /** "r/nome" ou "u/nome", como o Reddit mostra. */
    fun handle(username: String) = (if (isSubreddit(username)) "r/" else "u/") + nameOf(username)
    private fun path(username: String) = (if (isSubreddit(username)) "r/" else "user/") + nameOf(username)

    // ───────── HTTP com respeito ao limite do Reddit ─────────

    /** User-Agent no formato que a API do Reddit pede; UA de navegador sem navegador costuma levar 403. */
    private const val APP_UA = "android:com.example.profilearchive:v2.0 (archive app)"

    /**
     * token_v2 dos cookies: é o token que o próprio site usa para chamar oauth.reddit.com.
     * É um JWT; vencido (campo exp) não serve.
     */
    private fun bearer(cookies: List<CookieStore.Cookie>): String? {
        val t = cookies.firstOrNull { it.name == "token_v2" }?.value?.takeIf { it.isNotBlank() } ?: return null
        val exp = runCatching {
            JSONObject(String(android.util.Base64.decode(t.split('.')[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)))
                .optLong("exp", 0)
        }.getOrDefault(0)
        return t.takeIf { exp == 0L || exp * 1000 > System.currentTimeMillis() + 60_000 }
    }

    /** Interpreta o corpo: JSON (objeto ou lista) ou null se veio uma página (bloqueio). */
    private fun parse(body: String): Any? {
        val s = body.trimStart()
        return when {
            s.startsWith("[") -> runCatching { JSONArray(s) }.getOrNull()
            s.startsWith("{") -> runCatching { JSONObject(s) }.getOrNull()
            else -> null
        }
    }

    /**
     * GET de JSON. O Reddit informa quantas consultas restam (x-ratelimit-remaining) e quando o limite
     * zera (x-ratelimit-reset, em segundos): acabando, espera em vez de levar 429.
     * Ordem das tentativas:
     *  1. oauth.reddit.com com o token_v2 dos cookies (a rota do site logado);
     *  2. uma WebView (Chromium de verdade, com os cookies) pedindo o .json de dentro do reddit.com:
     *     o bloqueio do Reddit é para clientes que não são navegador, e ela é um navegador;
     *  3. www e old.reddit por HTTP simples, com User-Agent de app e de navegador.
     */
    private fun get(ctx: Context, pathAndQuery: String, onWait: (String) -> Unit): Any {
        val cookies = CookieStore.cookiesOf(ctx, "reddit")
        val cookie = cookies.joinToString("; ") { "${it.name}=${it.value}" }
        val token = bearer(cookies)
        var lastStatus = 0
        val trace = ArrayList<String>()                    // o que cada rota respondeu (vai na mensagem de erro)
        if (token == null) trace += if (cookies.any { it.name == "token_v2" }) "token_v2 vencido" else "sem token_v2"

        fun waitLimit(remaining: Double?, reset: Long) {
            if (remaining != null && remaining < 2) { onWait("Limite do Reddit, continuando em ${reset + 1} s…"); Thread.sleep((reset + 1) * 1000) }
        }

        // 1) oauth com o token do site
        if (token != null) for (attempt in 0..1) {
            val c = URL("https://oauth.reddit.com/$pathAndQuery").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", APP_UA)
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Authorization", "Bearer $token")
                lastStatus = c.responseCode
                trace += "oauth $lastStatus"
                val reset = c.getHeaderField("x-ratelimit-reset")?.toLongOrNull()?.coerceAtMost(120) ?: 60
                when (lastStatus) {
                    200 -> {
                        val r = parse(c.inputStream.bufferedReader().use { it.readText() })
                        if (r != null) { waitLimit(c.getHeaderField("x-ratelimit-remaining")?.toDoubleOrNull(), reset); return r }
                        break
                    }
                    429 -> { onWait("O Reddit pediu uma pausa, continuando em ${reset + 1} s…"); Thread.sleep((reset + 1) * 1000) }
                    404 -> throw Failed("Não encontrado no Reddit (o nome está certo?)")
                    else -> break
                }
            } catch (e: java.io.IOException) { lastStatus = -1; trace += "oauth sem conexão"; break } finally { c.disconnect() }
        }

        // 2) WebView
        for (attempt in 0..2) {
            val r = RedditWeb.fetch(ctx, cookies, "https://www.reddit.com/$pathAndQuery")
            if (r == null) { trace += "webview indisponível"; break }
            lastStatus = r.status
            trace += "webview ${r.status}" + if (r.status == 200 && parse(r.body) == null) " (página: ${r.body.replace(Regex("\\s+"), " ").take(60)})" else ""
            when (r.status) {
                200 -> { parse(r.body)?.let { waitLimit(r.remaining, r.reset); return it }; break }
                429 -> { onWait("O Reddit pediu uma pausa, continuando em ${r.reset + 1} s…"); Thread.sleep((r.reset + 1) * 1000) }
                404 -> throw Failed("Não encontrado no Reddit (o nome está certo?)")
                else -> break
            }
        }

        // 3) HTTP simples
        for ((host, ua) in listOf("https://www.reddit.com" to APP_UA, "https://old.reddit.com" to APP_UA,
                "https://www.reddit.com" to UA, "https://old.reddit.com" to UA)) {
            val c = URL("$host/$pathAndQuery").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Accept", "application/json")
                if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
                val st = c.responseCode
                trace += "${host.removePrefix("https://").substringBefore('.')} $st"
                if (lastStatus == 0 || st != 403) lastStatus = st
                when (st) {
                    200 -> parse(c.inputStream.bufferedReader().use { it.readText() })?.let { return it }
                    404 -> throw Failed("Não encontrado no Reddit (o nome está certo?)")
                }
            } catch (e: java.io.IOException) { if (lastStatus == 0) lastStatus = -1 } finally { c.disconnect() }
        }

        val hasLogin = cookies.any { it.name == "reddit_session" || it.name == "token_v2" }
        throw Failed("[" + trace.distinct().joinToString(" · ") + "] " + when {
            lastStatus == 429 -> "O Reddit limitou as consultas. Tente de novo em alguns minutos"
            lastStatus == -1 -> "Sem conexão com o Reddit"
            lastStatus == 403 || lastStatus == 401 || lastStatus == 200 -> if (hasLogin)
                "O Reddit recusou o acesso mesmo com os cookies (subreddit privado/banido, ou a sessão expirou: abra o reddit.com logado no navegador e cole os cookies de novo)"
                else "O Reddit recusou o acesso (perfil privado, banido ou bloqueio de rede). Cole os cookies do Reddit nas Configurações"
            else -> "O Reddit respondeu HTTP $lastStatus"
        })
    }

    // ───────── vídeos (v.redd.it) ─────────

    /**
     * Formatos do vídeo lidos do manifesto DASH do v.redd.it (melhor vídeo + melhor áudio, que o Reddit
     * serve separados; o yt-dlp junta). Assim o vídeo baixa direto, sem o yt-dlp abrir a página do post,
     * que o Reddit bloqueia. null = manifesto indisponível.
     */
    fun videoFormats(dashUrl: String): JSONArray? = runCatching {
        val c = URL(dashUrl).openConnection() as HttpURLConnection
        val xml = try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        val base = dashUrl.substringBefore('?').substringBeforeLast('/') + "/"
        class Rep(val video: Boolean, val w: Int, val h: Int, val bw: Long, val url: String)
        val reps = ArrayList<Rep>()
        val px = android.util.Xml.newPullParser().apply { setInput(java.io.StringReader(xml)) }
        var setMime = ""
        var cur: Array<Any?>? = null           // mime, w, h, bw, baseURL
        var inBase = false
        while (px.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (px.eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (px.name) {
                    "AdaptationSet" -> setMime = px.getAttributeValue(null, "mimeType") ?: px.getAttributeValue(null, "contentType") ?: ""
                    "Representation" -> cur = arrayOf(
                        px.getAttributeValue(null, "mimeType") ?: setMime,
                        px.getAttributeValue(null, "width")?.toIntOrNull() ?: 0,
                        px.getAttributeValue(null, "height")?.toIntOrNull() ?: 0,
                        px.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L, null)
                    "BaseURL" -> inBase = cur != null
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inBase) cur?.set(4, px.text.trim())
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (px.name) {
                    "BaseURL" -> inBase = false
                    "Representation" -> cur?.let { r ->
                        val u = (r[4] as String?)?.takeIf { it.isNotBlank() }
                        if (u != null) reps += Rep((r[0] as String).startsWith("video"), r[1] as Int, r[2] as Int, r[3] as Long,
                            if (u.startsWith("http")) u else base + u)
                        cur = null
                    }
                }
            }
        }
        val v = reps.filter { it.video }.maxWithOrNull(compareBy<Rep>({ it.h }, { it.bw })) ?: return null
        val a = reps.filter { !it.video }.maxByOrNull { it.bw }
        JSONArray().apply {
            put(JSONObject().put("url", v.url).put("ext", "mp4").put("format_id", "dash-v")
                .put("width", v.w).put("height", v.h).put("tbr", v.bw / 1000.0).put("vcodec", "avc1")
                .apply { if (a != null) put("acodec", "none") })
            if (a != null) put(JSONObject().put("url", a.url).put("ext", "m4a").put("format_id", "dash-a")
                .put("tbr", a.bw / 1000.0).put("vcodec", "none").put("acodec", "mp4a"))
        }
    }.getOrNull()

    // ───────── listagem ─────────

    /** Dados do perfil/subreddit no formato pa_* que o worker já entende. */
    private fun profileMeta(ctx: Context, username: String, onWait: (String) -> Unit): JSONObject {
        val d = runCatching { (get(ctx, "${path(username)}/about.json?raw_json=1", onWait) as JSONObject).optJSONObject("data") }.getOrNull()
            ?: return JSONObject()
        fun url(vararg keys: String) = keys.firstNotNullOfOrNull { k -> d.optString(k).takeIf { it.startsWith("http") } }
        return JSONObject().apply {
            if (isSubreddit(username)) {
                putOpt("pa_avatar", url("community_icon", "icon_img"))
                putOpt("pa_bio", d.optString("public_description").ifBlank { null })
                putOpt("pa_name", d.optString("title").ifBlank { null })
                if (d.has("subscribers")) put("pa_followers", d.optLong("subscribers"))
            } else {
                val sub = d.optJSONObject("subreddit")
                putOpt("pa_avatar", url("snoovatar_img", "icon_img"))
                putOpt("pa_bio", sub?.optString("public_description")?.ifBlank { null })
                putOpt("pa_name", sub?.optString("title")?.ifBlank { null })
                sub?.optLong("subscribers")?.takeIf { it > 0 }?.let { put("pa_followers", it) }
            }
        }
    }

    /**
     * Todos os posts com mídia que o Reddit deixa ver. A listagem do Reddit para em ~1000 itens,
     * então junta "novos" com "top de todos os tempos" para alcançar mais posts antigos.
     */
    fun list(ctx: Context, username: String, onProgress: (String) -> Unit): List<JSONObject> {
        val meta = profileMeta(ctx, username, onProgress)
        val base = if (isSubreddit(username)) listOf("${path(username)}/new.json?", "${path(username)}/top.json?t=all&")
            else listOf("${path(username)}/submitted.json?sort=new&", "${path(username)}/submitted.json?sort=top&t=all&")
        val out = LinkedHashMap<String, JSONObject>()
        var seen = 0
        for (b in base) {
            var after: String? = null
            for (page in 1..10) {
                onProgress("Listando posts… ${out.size} com mídia de $seen")
                val res = try { get(ctx, "${b}limit=100&raw_json=1" + (after?.let { "&after=$it" } ?: ""), onProgress) as JSONObject }
                    catch (e: Failed) { if (out.isEmpty() && b == base.first()) throw e else break }
                val data = res.optJSONObject("data") ?: break
                val children = data.optJSONArray("children") ?: break
                for (i in 0 until children.length()) {
                    val p = children.optJSONObject(i)?.optJSONObject("data") ?: continue
                    seen++
                    toEntry(p, meta)?.let { out.putIfAbsent(it.getString("id"), it) }
                }
                after = data.optString("after").ifBlank { null }
                if (after == null || after == "null" || children.length() == 0) break
                Thread.sleep(700)
            }
        }
        return out.values.toList()
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** Melhor imagem da prévia (o Reddit escapa "&" como "&amp;" sem raw_json; com raw_json vem limpo). */
    private fun previewSource(p: JSONObject) = p.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("source")

    private fun slide(url: String, w: Int?, h: Int?, video: Boolean = false, vararg fallback: String?) = JSONObject().apply {
        put("urls", JSONArray((listOf(url) + fallback.filterNotNull()).distinct()))
        putOpt("width", w); putOpt("height", h)
        if (video) put("video", true)
    }

    /** Fotos/galeria do post (null = não é post de fotos). */
    private fun slidesOf(p: JSONObject): List<JSONObject>? {
        // galeria: a ordem certa está em gallery_data; os links em media_metadata
        val metaMap = p.optJSONObject("media_metadata")
        if (p.optBoolean("is_gallery") && metaMap != null) {
            val order = p.optJSONObject("gallery_data")?.optJSONArray("items").objects().map { it.optString("media_id") }
                .ifEmpty { metaMap.keys().asSequence().toList() }
            return order.mapNotNull { mid ->
                val m = metaMap.optJSONObject(mid)?.takeIf { it.optString("status") == "valid" } ?: return@mapNotNull null
                val s = m.optJSONObject("s") ?: return@mapNotNull null
                val w = s.optInt("x").takeIf { it > 0 }; val h = s.optInt("y").takeIf { it > 0 }
                when {
                    s.optString("mp4").startsWith("http") -> slide(s.getString("mp4"), w, h, video = true)
                    s.optString("u").startsWith("http") -> slide(s.getString("u"), w, h)
                    s.optString("gif").startsWith("http") -> slide(s.getString("gif"), w, h)
                    else -> null
                }
            }.ifEmpty { null }
        }
        val url = p.optString("url_overridden_by_dest").ifBlank { p.optString("url") }
        val src = previewSource(p)
        val w = src?.optInt("width"); val h = src?.optInt("height")
        // gif do imgur (.gifv) é um mp4 de verdade
        if ("imgur.com" in url && url.endsWith(".gifv")) return listOf(slide(url.removeSuffix(".gifv") + ".mp4", w, h, video = true))
        val isImage = p.optString("post_hint") == "image" || (IMAGE_EXT.containsMatchIn(url) && ("redd.it" in url || "imgur.com" in url))
        if (!isImage) return null
        // gif animado: a versão mp4 da prévia é bem menor e toca no player
        if (url.lowercase().contains(".gif")) {
            val mp4 = p.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)
                ?.optJSONObject("variants")?.optJSONObject("mp4")?.optJSONObject("source")?.optString("url")
            if (!mp4.isNullOrBlank()) return listOf(slide(mp4, w, h, video = true))
        }
        return listOf(slide(url, w, h, false, src?.optString("url")))
    }

    /** Post da API → entrada no formato das do yt-dlp. Posts só de texto ou link externo ficam de fora. */
    private fun toEntry(post: JSONObject, meta: JSONObject): JSONObject? {
        // crosspost: a mídia está no post original
        val p = post.optJSONArray("crosspost_parent_list")?.optJSONObject(0) ?: post
        val id = post.optString("id").ifBlank { return null }
        if (post.has("removed_by_category") && !post.isNull("removed_by_category")) return null   // removido/apagado
        val slides = slidesOf(p)
        val video = p.optJSONObject("secure_media")?.optJSONObject("reddit_video") ?: p.optJSONObject("media")?.optJSONObject("reddit_video")
        if (slides == null && video == null) return null
        val src = previewSource(p)
        return JSONObject(meta.toString()).apply {
            put("id", id)
            put("url", "https://www.reddit.com" + post.optString("permalink").ifBlank { "/comments/$id/" })
            put("title", post.optString("title"))
            put("description", post.optString("selftext"))
            put("timestamp", post.optLong("created_utc"))
            put("like_count", post.optLong("score"))
            put("comment_count", post.optLong("num_comments"))
            put("uploader", post.optString("author"))
            put("channel", post.optString("subreddit_name_prefixed"))
            putOpt("thumbnail", src?.optString("url")?.takeIf { it.startsWith("http") })
            if (slides != null) {
                put("slides", JSONArray(slides))
                slides.firstOrNull()?.let { putOpt("width", it.opt("width")); putOpt("height", it.opt("height")) }
            } else {
                putOpt("width", video!!.optInt("width").takeIf { it > 0 }); putOpt("height", video.optInt("height").takeIf { it > 0 })
                putOpt("duration", video.optInt("duration").takeIf { it > 0 })
                putOpt("pa_dash", video.optString("dash_url").takeIf { it.startsWith("http") })
            }
        }
    }

    // ───────── comentários ─────────

    fun commentsFile(dir: File, id: String) = File(metaDir(dir), "$id.comments.json")

    /** Árvore de comentários simplificada: {a: autor, b: texto, s: pontos, t: data, op, r: respostas, more: quantos ficaram de fora}. */
    private fun simplify(children: JSONArray?, depth: Int = 0): JSONArray {
        val out = JSONArray()
        for (c in children.objects()) {
            val d = c.optJSONObject("data") ?: continue
            when (c.optString("kind")) {
                "t1" -> out.put(JSONObject().apply {
                    put("a", d.optString("author")); put("b", d.optString("body"))
                    put("s", d.optLong("score")); put("t", d.optLong("created_utc"))
                    if (d.optBoolean("is_submitter")) put("op", true)
                    val replies = d.optJSONObject("replies")?.optJSONObject("data")?.optJSONArray("children")
                    if (replies != null && replies.length() > 0) put("r", simplify(replies, depth + 1))
                })
                "more" -> d.optInt("count").takeIf { it > 0 }?.let { out.put(JSONObject().put("more", it)) }
            }
        }
        return out
    }

    /**
     * Baixa os comentários dos posts que ainda não têm (e atualiza os de posts com menos de 7 dias,
     * que ainda recebem comentários, uma vez por dia). Erro num post não para os outros.
     */
    fun fetchComments(ctx: Context, dir: File, posts: List<Pair<String, Long>>, onProgress: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val need = posts.filter { (id, created) ->
            val f = commentsFile(dir, id)
            !f.isFile || (now - created * 1000 < 7 * 86_400_000L && now - f.lastModified() > 86_400_000L)
        }
        need.forEachIndexed { i, (id, _) ->
            onProgress("Baixando comentários ${i + 1} de ${need.size}…")
            runCatching {
                val res = get(ctx, "comments/$id.json?raw_json=1&limit=500&depth=10&sort=top", onProgress) as JSONArray
                val tree = simplify(res.optJSONObject(1)?.optJSONObject("data")?.optJSONArray("children"))
                val count = res.optJSONObject(0)?.optJSONObject("data")?.optJSONArray("children")?.optJSONObject(0)
                    ?.optJSONObject("data")?.optLong("num_comments") ?: 0
                val f = commentsFile(dir, id)
                val tmp = File(f.parentFile, f.name + ".part")
                tmp.writeText(JSONObject().put("count", count).put("fetched", now / 1000).put("comments", tree).toString())
                f.delete(); tmp.renameTo(f)
            }.onFailure { if (it is Failed && "limitou" in it.message.orEmpty()) return }   // limite: para e tenta na próxima vez
            Thread.sleep(600)
        }
    }

    /** Comentário achatado para a lista (profundidade = recuo). */
    class Comment(val author: String, val body: String, val score: Long, val time: Long, val depth: Int, val op: Boolean, val more: Int)

    fun readComments(dir: File, id: String): Pair<Long, List<Comment>>? {
        val j = runCatching { JSONObject(commentsFile(dir, id).readText()) }.getOrNull() ?: return null
        val out = ArrayList<Comment>()
        fun walk(arr: JSONArray?, depth: Int) {
            for (c in arr.objects()) {
                if (c.has("more")) { out += Comment("", "", 0, 0, depth, false, c.optInt("more")); continue }
                out += Comment(c.optString("a"), c.optString("b"), c.optLong("s"), c.optLong("t") * 1000, depth, c.optBoolean("op"), 0)
                walk(c.optJSONArray("r"), depth + 1)
            }
        }
        walk(j.optJSONArray("comments"), 0)
        return j.optLong("count") to out
    }
}

/**
 * WebView invisível (sem tela) que ABRE a URL do .json como uma página, exatamente como digitar o
 * endereço no Chrome logado: mesmos cabeçalhos de navegação, mesmos cookies, User-Agent de Chrome.
 * O texto da página (o JSON) é lido por JavaScript. Fica viva entre as consultas de uma verificação
 * e é destruída 90 s depois da última. Chamar fora da thread principal: espera a resposta.
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
internal object RedditWeb {
    class Res(val status: Int, val body: String, val remaining: Double?, val reset: Long)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var web: android.webkit.WebView? = null
    private val lock = Any()
    private val stop = Runnable { web?.destroy(); web = null }

    // pedido em andamento (um por vez)
    @Volatile private var curId = 0
    @Volatile private var curStatus = 200
    @Volatile private var curFuture: java.util.concurrent.CompletableFuture<Res>? = null
    private val ids = java.util.concurrent.atomic.AtomicInteger()

    class Bridge {
        @android.webkit.JavascriptInterface
        fun page(id: Int, text: String?) {
            if (id == curId) curFuture?.complete(Res(curStatus, text.orEmpty(), null, 60))
        }
    }

    private fun create(ctx: Context, cookies: List<CookieStore.Cookie>): android.webkit.WebView {
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cookies.forEach { c ->
            val domain = c.domain.ifBlank { ".reddit.com" }
            cm.setCookie("https://www.reddit.com", "${c.name}=${c.value}; Domain=$domain; Path=/; Secure")
        }
        cm.flush()
        return android.webkit.WebView(ctx.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkImage = true
            // UA do Chrome de verdade (sem o "; wv" e o "Version/4.0" que denunciam a WebView)
            settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
            cm.setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Bridge(), "PA")
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onReceivedHttpError(view: android.webkit.WebView, req: android.webkit.WebResourceRequest,
                                                 resp: android.webkit.WebResourceResponse) {
                    if (req.isForMainFrame) curStatus = resp.statusCode
                }
                override fun onReceivedError(view: android.webkit.WebView, req: android.webkit.WebResourceRequest,
                                             err: android.webkit.WebResourceError) {
                    if (req.isForMainFrame) curFuture?.complete(Res(-1, err.description?.toString().orEmpty(), null, 60))
                }
                override fun onPageFinished(view: android.webkit.WebView, u: String) {
                    val id = curId
                    view.evaluateJavascript("PA.page($id, document.body ? document.body.innerText : (document.documentElement ? document.documentElement.innerText : ''))", null)
                }
            }
        }
    }

    /** null = WebView indisponível neste aparelho. */
    fun fetch(ctx: Context, cookies: List<CookieStore.Cookie>, url: String): Res? = synchronized(lock) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null
        val f = java.util.concurrent.CompletableFuture<Res>()
        val started = java.util.concurrent.CompletableFuture<Boolean>()
        val id = ids.incrementAndGet()
        main.post {
            main.removeCallbacks(stop)
            try {
                val w = web ?: create(ctx, cookies).also { web = it }
                curId = id; curStatus = 200; curFuture = f
                w.loadUrl(url)
                started.complete(true)
            } catch (e: Throwable) { started.complete(false) }
        }
        if (runCatching { started.get(15, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() != true) { main.post(stop); return null }
        val res = runCatching { f.get(45, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        curFuture = null
        main.postDelayed(stop, 90_000)
        res ?: Res(-1, "sem resposta em 45 s", null, 60)
    }
}
