package com.example.profilearchive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

val SEC_UID = Regex("MS4wLjABAAAA[\\w-]{20,}")

// ───────────── secUid (identificador interno da conta) ─────────────

/** secUid já conhecido: arquivo salvo ou campo channel_id de algum .info.json baixado antes. */
fun cachedSecUid(dir: File): String? {
    val saved = File(metaDir(dir), "secuid.txt")
    saved.takeIf { it.isFile }?.readText()?.trim()?.takeIf { SEC_UID.matches(it) }?.let { return it }
    val re = Regex("\"channel_id\"\\s*:\\s*\"(${SEC_UID.pattern})\"")
    metaDir(dir).listFiles().orEmpty().asSequence()
        .filter { it.name.endsWith(".info.json") }.take(5)
        .mapNotNull { runCatching { re.find(it.readText())?.groupValues?.get(1) }.getOrNull() }
        .firstOrNull()?.let { saveSecUid(dir, it); return it }
    return null
}

fun saveSecUid(dir: File, secUid: String) = runCatching { File(metaDir(dir), "secuid.txt").writeText(secUid) }

// ───────────── downloads diretos (fotos, música, avatar) ─────────────

/**
 * Baixa [url] para "<base>.part.<ext>" e devolve o arquivo parcial (quem chama renomeia no final).
 * A extensão vem do content-type; [audio] escolhe a tabela de áudio em vez da de imagem.
 */
private fun fetchPart(url: String, dir: File, base: String, audio: Boolean): File {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", BROWSER_UA)
        if ("tiktok" in url) c.setRequestProperty("Referer", "https://www.tiktok.com/")
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        val type = c.contentType.orEmpty().lowercase()
        val ext = if (audio) when {
            "mpeg" in type || "mp3" in type || ".mp3" in url -> "mp3"
            "aac" in type -> "aac"
            else -> "m4a"
        } else when {
            "video" in type || ".mp4" in url -> "mp4"        // vídeo dentro de carrossel (Instagram)
            "webp" in type -> "webp"; "png" in type -> "png"; "heic" in type || "heif" in type -> "heic"; else -> "jpg"
        }
        val part = File(dir, "$base.part.$ext")
        c.inputStream.use { i -> part.outputStream().use { i.copyTo(it) } }
        if (part.length() == 0L) { part.delete(); error("arquivo vazio") }
        return part
    } finally { c.disconnect() }
}

/** Primeira URL que funcionar; JPEG antes de WEBP/HEIC por compatibilidade. */
private fun fetchAny(urls: List<String>, dir: File, base: String, audio: Boolean): Pair<File?, String?> {
    var err: String? = null
    for (u in urls.sortedBy { if (audio || "jpeg" in it || ".jpg" in it) 0 else 1 }) {
        try { return fetchPart(u, dir, base, audio) to null } catch (e: Exception) { err = e.message }
    }
    return null to (err ?: "sem link")
}

/** Áudio de um post de fotos que já está no aparelho sem ele (ex.: música do Instagram). Devolve o arquivo. */
fun downloadPostAudio(dir: File, id: String, url: String): File? {
    val (part, _) = fetchAny(listOf(url), dir, id, audio = true)
    part ?: return null
    val target = File(dir, part.name.replace(".part.", "."))
    target.delete()
    return if (part.renameTo(target)) target else { part.delete(); null }
}

/** Foto de perfil (substitui a anterior só se o download completar). */
fun downloadAvatar(url: String, target: File): Boolean = runCatching {
    val part = fetchPart(url, target.parentFile!!, "pfp", audio = false)
    target.delete(); part.renameTo(target)
}.getOrDefault(false)

/**
 * Post de fotos (TikTok, Instagram; carrossel do Instagram pode ter vídeos), tudo ou nada: baixa todas as fotos e a música como ".part";
 * só se TUDO der certo renomeia para os nomes finais e grava o índice .slides.
 * Se qualquer arquivo falhar, apaga o que baixou e devolve o erro (o post é tentado de novo depois).
 *
 * @param info entrada do yt-dlp com "slides" (e "slides_audio") vinda do plugin
 * @return null se deu certo, senão a mensagem de erro
 */
fun downloadPhotoPost(dir: File, id: String, info: JSONObject): String? {
    val slides = info.optJSONArray("slides") ?: return "post sem a lista de fotos"
    val parts = ArrayList<Pair<File, File>>()           // .part → nome final
    fun abort(msg: String): String { parts.forEach { it.first.delete() }; return msg }

    for (i in 0 until slides.length()) {
        val urls = slides.optJSONObject(i)?.optJSONArray("urls").strings()
        val (part, err) = fetchAny(urls, dir, "%s.slide%02d".format(id, i + 1), audio = false)
        part ?: return abort("foto ${i + 1} de ${slides.length()} não baixou ($err)")
        parts += part to File(dir, part.name.replace(".part.", "."))
    }
    // música: reaproveita a que já estiver no aparelho (versões antigas baixavam só ela)
    val existingAudio = finishedFiles(dir)[id]?.takeIf { it.extension.lowercase() in AUDIO_EXTS }
    val audioUrl = info.str("slides_audio")
    if (existingAudio == null && audioUrl != null) {
        // música que falhar não derruba o post: as fotos ficam e a música é buscada depois pelo yt-dlp
        val (part, _) = fetchAny(listOf(audioUrl), dir, id, audio = true)
        part?.let { parts += it to File(dir, it.name.replace(".part.", ".")) }
    }

    parts.forEach { (part, target) -> target.delete(); if (!part.renameTo(target)) return abort("falha ao salvar ${target.name}") }
    // capa do post (miniatura do grid), se o site mandou: falha aqui não invalida o post
    info.str("thumbnail")?.takeIf { metaDir(dir).listFiles().orEmpty().none { f -> f.name.startsWith("$id.thumb.") } }?.let { u ->
        val (part, _) = fetchAny(listOf(u), metaDir(dir), "$id.thumb", audio = false)
        part?.renameTo(File(metaDir(dir), part.name.replace(".part.", ".")))
    }
    // metadados (sem os links temporários) e, por último, o índice que marca o post como completo
    if (!infoFile(dir, id).isFile) infoFile(dir, id).writeText(JSONObject(info.toString()).apply { remove("pa_formats") }.toString())
    val images = parts.map { it.second }.filter { it.extension.lowercase() !in AUDIO_EXTS }
    slidesIndex(dir, id).writeText(images.joinToString("\n") { it.path })
    return null
}

/**
 * Remove música solta (post de fotos sem as fotos) e tira o id do archive.txt,
 * para o post inteiro ser baixado de novo na próxima atualização.
 */
fun dropOrphanAudio(dir: File): Set<String> {
    val orphans = finishedFiles(dir).filter { (id, f) -> f.extension.lowercase() in AUDIO_EXTS && !slidesIndex(dir, id).isFile }
    orphans.values.forEach { it.delete() }
    forgetInArchive(dir, orphans.keys)
    return orphans.keys
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.startsWith("http") }

// ───────────── música do post (disco que gira no player) ─────────────

/** Música de um post: nome, autor, se é áudio original e a capa já baixada (null = ainda não tem). */
class Music(val title: String?, val author: String?, val original: Boolean, val cover: File?, val contains: Boolean = false)

/** Capa fica em .dados/musica/<hash do link>: posts com a mesma música dividem o mesmo arquivo. */
private fun musicCover(dir: File, url: String) =
    File(File(metaDir(dir), "musica"), "%08x".format(url.substringBefore('?').hashCode()))

/**
 * Lê a música do .info.json: "pa_music" (plugin) ou, em dados antigos, os campos padrão do yt-dlp
 * (track/artists). Sem nada disso devolve null.
 */
fun readMusic(dir: File, info: JSONObject): Music? {
    val m = info.optJSONObject("pa_music")
    fun s(o: JSONObject?, k: String) = o?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
    val title = s(m, "title") ?: s(info, "track")
    val author = s(m, "author") ?: info.optJSONArray("artists")?.optString(0)?.takeIf { it.isNotBlank() } ?: s(info, "artist")
    val original = m?.optBoolean("original") == true ||
        (title != null && (title.startsWith("original sound", true) || title.startsWith("som original", true)))
    if (title == null && author == null) return null
    val cover = s(m, "cover")?.let { musicCover(dir, it) }?.takeIf { it.isFile }
    // "contains": som original em que o TikTok reconheceu uma música (mostra "Contém: <música>")
    return Music(title, author, original, cover, contains = m?.optBoolean("contains") == true)
}

/** Grava "pa_music" nos .info.json que ainda não têm (id do post → música da listagem). */
fun addMissingMusic(dir: File, music: List<Pair<String, JSONObject>>) {
    for ((id, m) in music) {
        val f = File(metaDir(dir), "$id.info.json")
        if (!f.isFile) continue
        runCatching {
            val j = JSONObject(f.readText())
            // grava se não tem ou se a música mudou (ex.: agora com a música reconhecida no lugar do som original)
            val old = j.optJSONObject("pa_music")
            if (old == null || old.optString("title") != m.optString("title") || old.optString("author") != m.optString("author"))
                f.writeText(j.put("pa_music", m).toString())
        }
    }
}

/** Baixa as capas de música que faltam (melhor esforço, 4 por vez; falha tenta de novo na próxima verificação). */
fun downloadMusicCovers(dir: File) {
    val urls = metaDir(dir).listFiles().orEmpty().asSequence()
        .filter { it.name.endsWith(".info.json") }
        .mapNotNull { f -> runCatching { JSONObject(f.readText()).optJSONObject("pa_music")?.optString("cover") }.getOrNull() }
        .filter { it.startsWith("http") }
        .distinctBy { musicCover(dir, it).name }
        .filterNot { musicCover(dir, it).isFile }
        .toList()
    if (urls.isEmpty()) return
    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    try {
        urls.map { u ->
            pool.submit {
                runCatching {
                    val target = musicCover(dir, u).also { it.parentFile?.mkdirs() }
                    val part = fetchPart(u, target.parentFile!!, target.name, audio = false)
                    if (!part.renameTo(target)) part.delete()
                }
            }
        }.forEach { runCatching { it.get() } }
    } finally { pool.shutdown() }
}
