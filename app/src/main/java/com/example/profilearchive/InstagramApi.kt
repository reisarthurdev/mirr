package com.example.profilearchive

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Listagem de perfis do Instagram por HTTP direto, sem WebView e sem o python do yt-dlp.
 *
 * Com login (cookies com "sessionid" colados nas Configurações) usa a API do APP do Instagram
 * (i.instagram.com/api/v1), o mesmo caminho que o instagrapi usa ao entrar com sessionid:
 * cabeçalhos do app Android + "Authorization: Bearer IGT:2:<ds_user_id, sessionid>". É a rota que o
 * Instagram menos limita, porque é a que o próprio app usa o tempo todo.
 *   1. users/<usuário>/usernameinfo → id, foto em alta, bio, seguidores, nº de posts
 *   2. feed/user/<id> paginado (12 por página, pausa de 2 a 4 s entre páginas, ritmo de quem rola o perfil)
 *
 * Sem login: web_profile_info do site, que entrega o perfil e os 12 posts mais recentes
 * (o Instagram não deixa ver além disso sem conta).
 *
 * Cada post vira um JSON no mesmo formato do plugin do yt-dlp (slides / pa_formats / thumbnail…),
 * então o resto do download não muda.
 */
class InstagramApi private constructor(private val cookies: Map<String, String>) {

    /** Erro definitivo da consulta: o worker para de insistir e mostra a mensagem. */
    open class Stop(msg: String) : Exception(msg)
    /** O Instagram pediu para esperar (429 ou "Please wait a few minutes"). */
    class Limited(msg: String) : Stop(msg)

    private val sessionId = cookies["sessionid"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    // o sessionid começa com o id da conta ("12345:abc…"): serve quando o ds_user_id não foi colado
    private val userId = cookies["ds_user_id"] ?: sessionId?.substringBefore(':')?.takeIf { it.all(Char::isDigit) }
    val loggedIn = sessionId != null

    /** GET → (status, corpo). Rota do app (logado) ou do site (sem login). */
    private fun get(url: String, app: Boolean): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Accept-Language", "pt-BR")
            c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            cookies["csrftoken"]?.let { c.setRequestProperty("X-CSRFToken", it) }
            if (app) {
                c.setRequestProperty("User-Agent", APP_UA)
                c.setRequestProperty("X-IG-App-ID", APP_ID)
                c.setRequestProperty("X-IG-Capabilities", "3brTv10=")
                c.setRequestProperty("X-IG-Connection-Type", "WIFI")
                if (sessionId != null && userId != null) {
                    val auth = JSONObject().put("ds_user_id", userId).put("sessionid", sessionId).toString()
                    c.setRequestProperty("Authorization", "Bearer IGT:2:" + Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP))
                }
            } else {
                c.setRequestProperty("User-Agent", WEB_UA)
                c.setRequestProperty("X-IG-App-ID", WEB_APP_ID)
                c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                c.setRequestProperty("Referer", "https://www.instagram.com/")
            }
            val status = c.responseCode
            val body = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to body
        } catch (e: java.io.IOException) {
            throw Stop("Sem conexão com o Instagram (${e.message})")
        } finally { c.disconnect() }
    }

    /**
     * GET que devolve JSON. "Espere" do Instagram (429 ou 401 "Please wait a few minutes") vira pausa
     * de verdade e nova tentativa (1, 3 e 6 min); o resto vira [Stop] com o motivo legível.
     */
    private suspend fun json(url: String, app: Boolean, onWait: suspend (String) -> Unit): JSONObject {
        val waits = listOf(60, 180, 360)
        for (attempt in 0..waits.size) {
            coroutineContext.ensureActive()
            val (status, body) = get(url, app)
            val json = runCatching { JSONObject(body) }.getOrNull()
            val message = json?.optString("message").orEmpty()
            val limited = status == 429 || "wait a few minutes" in message.lowercase()
            when {
                status == 200 && json != null && json.optString("status") != "fail" -> return json
                limited && attempt < waits.size -> {
                    onWait("O Instagram pediu uma pausa, continuando em ${waits[attempt] / 60} min…")
                    delay(waits[attempt] * 1000L)
                }
                limited -> throw Limited("O Instagram limitou as consultas. Espere uns 30 minutos e tente de novo.")
                status == 404 -> throw Stop("Perfil não encontrado no Instagram")
                message == "login_required" || json?.optBoolean("require_login") == true || status == 401 || status == 403 ->
                    throw Stop(if (loggedIn) "O Instagram recusou a sessão dos cookies (expirou ou foi desconectada). Entre de novo no navegador e cole os cookies novos"
                        else "O Instagram pediu login. Cole nas Configurações os cookies de uma conta logada (precisa ter o \"sessionid\")")
                status == 200 -> throw Stop("O Instagram devolveu uma página em vez dos dados (bloqueio). Tente de novo mais tarde")
                else -> throw Stop("O Instagram respondeu HTTP $status${if (message.isNotBlank()) ": $message" else ""}")
            }
        }
        throw Limited("O Instagram limitou as consultas. Espere uns 30 minutos e tente de novo.")
    }

    /** Dados do perfil no formato pa_* que o worker já entende. */
    private fun meta(avatar: String?, bio: String?, name: String?, followers: Long?, total: Int?) = JSONObject().apply {
        putOpt("pa_avatar", avatar?.ifBlank { null }); putOpt("pa_bio", bio?.ifBlank { null })
        putOpt("pa_name", name?.ifBlank { null }); putOpt("pa_followers", followers); putOpt("pa_total", total)
    }

    /** Lista o perfil inteiro (logado) ou os 12 posts mais recentes (sem login). */
    suspend fun listProfile(username: String, onProgress: suspend (String) -> Unit): List<JSONObject> =
        if (loggedIn) listWithApp(username, onProgress) else listPublic(username, onProgress)

    private suspend fun listPublic(username: String, onProgress: suspend (String) -> Unit): List<JSONObject> {
        onProgress("Lendo perfil…")
        val user = json("https://www.instagram.com/api/v1/users/web_profile_info/?username=$username", app = false, onProgress)
            .optJSONObject("data")?.optJSONObject("user") ?: throw Stop("Perfil não encontrado no Instagram")
        val timeline = user.optJSONObject("edge_owner_to_timeline_media")
        val m = meta(user.optString("profile_pic_url_hd").ifBlank { user.optString("profile_pic_url") }, user.optString("biography"),
            user.optString("full_name"), user.optJSONObject("edge_followed_by")?.optLong("count"), timeline?.optInt("count"))
        if (user.optBoolean("is_private")) throw Stop("Perfil privado: só dá para baixar com os cookies de uma conta que segue esse perfil")
        return timeline?.optJSONArray("edges").objects().mapNotNull { fromNode(it.optJSONObject("node"), m) }
    }

    private suspend fun listWithApp(username: String, onProgress: suspend (String) -> Unit): List<JSONObject> {
        onProgress("Lendo perfil…")
        val user = json("https://i.instagram.com/api/v1/users/$username/usernameinfo/", app = true, onProgress)
            .optJSONObject("user") ?: throw Stop("Perfil não encontrado no Instagram")
        val pk = user.optString("pk").ifBlank { user.optString("pk_id") }.ifBlank { throw Stop("Perfil não encontrado no Instagram") }
        val m = meta(user.optJSONObject("hd_profile_pic_url_info")?.optString("url")?.ifBlank { null } ?: user.optString("profile_pic_url"),
            user.optString("biography"), user.optString("full_name"),
            user.optLong("follower_count").takeIf { user.has("follower_count") }, user.optInt("media_count").takeIf { user.has("media_count") })

        val out = LinkedHashMap<String, JSONObject>()
        var maxId: String? = null
        var page = 1
        while (true) {
            onProgress("Listando posts… página $page (${out.size} até agora)")
            val q = "count=12" + (maxId?.let { "&max_id=$it" } ?: "")
            // bloqueio no meio da paginação: fica com o que já veio (o resto aparece como "não listado")
            val feed = try { json("https://i.instagram.com/api/v1/feed/user/$pk/?$q", app = true, onProgress) }
                catch (e: Stop) { if (out.isEmpty()) throw e; break }
            val items = feed.optJSONArray("items").objects()
            items.forEach { fromFeed(it, m)?.let { e -> out.putIfAbsent(e.getString("id"), e) } }
            maxId = feed.optString("next_max_id").ifBlank { null }
            if (items.isEmpty() || !feed.optBoolean("more_available") || maxId == null) break
            page++
            delay(2_000L + Random.nextLong(2_000))
        }
        return out.values.toList()
    }

    // ───────── conversão para o formato das entradas do yt-dlp ─────────

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** Candidatos {url, width, height} do maior para o menor. */
    private fun bySize(arr: JSONArray?) = arr.objects().filter { it.optString("url").startsWith("http") }
        .sortedByDescending { it.optLong("width") * it.optLong("height") }

    private fun base(code: String, meta: JSONObject) = JSONObject(meta.toString()).apply {
        put("id", code); put("url", "https://www.instagram.com/p/$code/")
    }

    private fun slideFromFeed(m: JSONObject): JSONObject? {
        val images = bySize(m.optJSONObject("image_versions2")?.optJSONArray("candidates"))
        val videos = bySize(m.optJSONArray("video_versions"))
        val best = if (m.optInt("media_type") == 2 && videos.isNotEmpty()) videos else images
        if (best.isEmpty()) return null
        return JSONObject().apply {
            put("urls", JSONArray(best.map { it.getString("url") }))
            put("width", best[0].optInt("width")); put("height", best[0].optInt("height"))
            if (best === videos) put("video", true)
        }
    }

    private fun fromFeed(item: JSONObject?, meta: JSONObject): JSONObject? {
        if (item == null) return null
        val code = item.optString("code").ifBlank { return null }
        val caption = item.optJSONObject("caption")?.optString("text").orEmpty()
        return base(code, meta).apply {
            put("title", caption.take(72).ifBlank { code }); put("description", caption)
            put("timestamp", item.optLong("taken_at"))
            put("like_count", item.optLong("like_count")); put("comment_count", item.optLong("comment_count"))
            if (item.has("play_count") || item.has("view_count")) put("view_count", item.optLong("play_count", item.optLong("view_count")))
            put("width", item.optInt("original_width")); put("height", item.optInt("original_height"))
            bySize(item.optJSONObject("image_versions2")?.optJSONArray("candidates")).firstOrNull()?.let { c -> put("thumbnail", c.getString("url")) }
            // música escolhida para o post (fotos/carrossel): baixada junto e tocada só no trecho escolhido
            musicOf(item)?.let { (song, url) ->
                put("pa_music", song)
                if (item.optInt("media_type") != 2) put("slides_audio", url)   // vídeo já tem o áudio dentro
            }
            when (item.optInt("media_type")) {
                2 -> put("pa_formats", JSONArray(bySize(item.optJSONArray("video_versions")).mapIndexed { i, v ->
                    JSONObject().put("url", v.getString("url")).put("width", v.optInt("width")).put("height", v.optInt("height"))
                        .put("ext", "mp4").put("format_id", "feed-$i")
                }))
                8 -> put("slides", JSONArray(item.optJSONArray("carousel_media").objects().mapNotNull(::slideFromFeed)))
                else -> put("slides", JSONArray(listOfNotNull(slideFromFeed(item))))
            }
        }
    }

    /** Primeiro número com essa chave em qualquer nível do JSON (o Instagram muda onde guarda o trecho). */
    private fun findLong(o: Any?, key: String): Long? = when (o) {
        is JSONObject -> if (o.has(key) && !o.isNull(key)) o.optLong(key)
            else o.keys().asSequence().firstNotNullOfOrNull { findLong(o.opt(it), key) }
        is JSONArray -> (0 until o.length()).firstNotNullOfOrNull { findLong(o.opt(it), key) }
        else -> null
    }

    /**
     * Música do post (music_metadata da API do app): nome, artista, capa, o link do áudio e o trecho
     * escolhido pelo autor (início e duração, em ms). Som original de vídeo não entra (já está no vídeo).
     */
    private fun musicOf(item: JSONObject): Pair<JSONObject, String>? {
        val meta = item.optJSONObject("music_metadata") ?: item.optJSONObject("clips_metadata")?.optJSONObject("music_info")?.let {
            JSONObject().put("music_info", it)
        } ?: return null
        val asset = meta.optJSONObject("music_info")?.optJSONObject("music_asset_info") ?: return null
        val url = asset.optString("progressive_download_url").ifBlank { asset.optString("fast_start_progressive_download_url") }
            .takeIf { it.startsWith("http") } ?: return null
        val start = findLong(meta, "audio_asset_start_time_in_ms")
            ?: asset.optJSONArray("highlight_start_times_in_ms")?.optLong(0)
        val length = findLong(meta, "overlap_duration_in_ms")
        val song = JSONObject().apply {
            putOpt("title", asset.optString("title").ifBlank { null })
            putOpt("author", asset.optString("display_artist").ifBlank { null })
            putOpt("cover", asset.optString("cover_artwork_uri").ifBlank { asset.optString("cover_artwork_thumbnail_uri") }.ifBlank { null })
            put("original", false)
            putOpt("start_ms", start); putOpt("length_ms", length?.takeIf { it > 0 })
        }
        return song to url
    }

    private fun slideFromNode(n: JSONObject): JSONObject? {
        val dims = n.optJSONObject("dimensions")
        val url = (if (n.optBoolean("is_video")) n.optString("video_url") else n.optString("display_url")).ifBlank { return null }
        return JSONObject().put("urls", JSONArray(listOf(url))).put("width", dims?.optInt("width")).put("height", dims?.optInt("height"))
            .apply { if (n.optBoolean("is_video")) put("video", true) }
    }

    private fun fromNode(n: JSONObject?, meta: JSONObject): JSONObject? {
        if (n == null) return null
        val code = n.optString("shortcode").ifBlank { return null }
        val caption = n.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.optJSONObject(0)
            ?.optJSONObject("node")?.optString("text").orEmpty()
        val dims = n.optJSONObject("dimensions")
        return base(code, meta).apply {
            put("title", caption.take(72).ifBlank { code }); put("description", caption)
            put("timestamp", n.optLong("taken_at_timestamp"))
            put("width", dims?.optInt("width")); put("height", dims?.optInt("height"))
            putOpt("thumbnail", n.optString("display_url").ifBlank { null })
            val children = n.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges").objects().mapNotNull { it.optJSONObject("node") }
            when {
                children.isNotEmpty() -> put("slides", JSONArray(children.mapNotNull(::slideFromNode)))
                n.optBoolean("is_video") -> n.optString("video_url").ifBlank { null }?.let { u ->
                    put("pa_formats", JSONArray().put(JSONObject().put("url", u).put("width", dims?.optInt("width"))
                        .put("height", dims?.optInt("height")).put("ext", "mp4").put("format_id", "graphql")))
                }
                else -> put("slides", JSONArray(listOfNotNull(slideFromNode(n))))
            }
        }
    }

    companion object {
        // app Android do Instagram (rota com login) e site (rota sem login)
        private const val APP_ID = "567067343352427"
        private const val APP_UA = "Instagram 385.0.0.47.74 Android (34/14; 450dpi; 1080x2340; samsung; SM-S711B; r11s; s5e9945; pt_BR; 756700180)"
        private const val WEB_APP_ID = "936619743392459"
        private const val WEB_UA = "Mozilla/5.0 (Linux; Android 14; SM-S711B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        /** Cliente com os cookies do Instagram guardados nas Configurações. */
        fun create(ctx: Context) = InstagramApi(CookieStore.cookiesOf(ctx, "instagram").associate { it.name to it.value })
    }
}
