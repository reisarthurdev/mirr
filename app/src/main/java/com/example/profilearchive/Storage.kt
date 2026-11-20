package com.example.profilearchive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

// ───────────── layout em disco ─────────────
// <pasta>/<Aplicativo>/<usuário>/pfp.jpg
// <pasta>/<Aplicativo>/<usuário>/<id>.mp4 | <id>.mkv | <id>.jpg                 ← vídeo / foto
// <pasta>/<Aplicativo>/<usuário>/<id>.slide01.jpg … [+ <id>.mp3]                 ← post de fotos do TikTok
// <pasta>/<Aplicativo>/<usuário>/.dados/<id>.info.json, <id>.thumb.*, <id>.slides, archive.txt, secuid.txt
//
// Um post só "existe" quando está completo: vídeo inteiro, ou TODAS as fotos (+ música, se houver).
// Nada de música solta sem as fotos.

val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "m4v")
val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "opus")
val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "heic")
private val EXTS = VIDEO_EXTS + AUDIO_EXTS + IMAGE_EXTS

fun metaDir(dir: File) = File(dir, ".dados")
fun infoFile(dir: File, id: String) = File(metaDir(dir), "$id.info.json")
/** Lista das fotos de um post de fotos. Só é gravado quando todas foram baixadas: é o "concluído" do post. */
fun slidesIndex(dir: File, id: String) = File(metaDir(dir), "$id.slides")
private fun archiveFile(dir: File) = File(metaDir(dir), "archive.txt")

fun JSONObject.longOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null

fun readInfo(dir: File, id: String): JSONObject? = runCatching { JSONObject(infoFile(dir, id).readText()) }.getOrNull()

/** ids que o yt-dlp já registrou como baixados */
fun archiveIds(dir: File): Set<String> = runCatching {
    archiveFile(dir).readLines().mapNotNull { it.trim().substringAfterLast(' ').takeIf { s -> s.isNotEmpty() } }.toSet()
}.getOrDefault(emptySet())

/** Tira ids do archive.txt (post incompleto apagado: tem que ser baixado de novo na próxima vez). */
fun forgetInArchive(dir: File, ids: Set<String>) {
    if (ids.isEmpty()) return
    val f = archiveFile(dir)
    if (!f.isFile) return
    f.writeText(f.readLines().filter { it.trim().substringAfterLast(' ') !in ids }.joinToString("\n", postfix = "\n"))
}

/** arquivos de mídia concluídos (nome exato <id>.<ext>; partes como <id>.f137.mp4, .part e slides não contam) */
fun finishedFiles(dir: File): Map<String, File> {
    val out = HashMap<String, File>()
    dir.listFiles().orEmpty().forEach { f ->
        if (!f.isFile) return@forEach
        val ext = f.extension.lowercase()
        if (ext !in EXTS) return@forEach
        val base = f.name.removeSuffix("." + f.extension)
        if ('.' !in base && base != "pfp") out[base] = f
    }
    return out
}

/** ids de posts de fotos completos */
fun completedSlideIds(dir: File): Set<String> =
    metaDir(dir).listFiles().orEmpty().mapNotNullTo(HashSet()) { f -> f.name.takeIf { it.endsWith(".slides") }?.removeSuffix(".slides") }

/** Posts completos no aparelho: vídeo/foto inteiro ou post de fotos com todas as imagens. Música solta não conta. */
fun completedIds(dir: File): Set<String> =
    finishedFiles(dir).filterValues { it.extension.lowercase() !in AUDIO_EXTS }.keys + completedSlideIds(dir)

/**
 * Versões anteriores usavam o modelo "-o infojson:<id>.info.json" e o yt-dlp acrescenta ".info.json"
 * sozinho: os arquivos ficavam "<id>.info.json.info.json" e NENHUM post era indexado (perfil vazio).
 */
fun fixInfoNames(dir: File) {
    metaDir(dir).listFiles().orEmpty().forEach { f ->
        if (f.name.endsWith(".info.json.info.json")) {
            val target = File(f.parentFile, f.name.removeSuffix(".info.json"))
            if (!target.exists()) f.renameTo(target) else f.delete()
        }
    }
}

/**
 * Pasta do perfil. Se já existir uma com o mesmo nome em outra caixa (ex.: "Fulano" x "fulano"),
 * reaproveita ela em vez de criar outra e baixar tudo de novo.
 */
fun profileDir(base: String, label: String, safeName: String): File {
    val parent = File(base, label)
    val exact = File(parent, safeName)
    if (exact.isDirectory) return exact
    return parent.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name.equals(safeName, ignoreCase = true) } ?: exact
}

/**
 * Arquivo com o id no nome mas fora do padrão (ex.: "titulo [id].mp4", de outro app ou do yt-dlp no PC)
 * é renomeado para <id>.<ext>, o nome que o app reconhece. Retorna os ids que já tinham mídia.
 */
fun adoptRenamed(dir: File, ids: Set<String>): Set<String> {
    val canonical = finishedFiles(dir).toMutableMap()
    val splitter = Regex("[^A-Za-z0-9_-]+")
    dir.listFiles().orEmpty().forEach { f ->
        val ext = f.extension.lowercase()
        if (!f.isFile || ext !in EXTS || f.name.startsWith("pfp.") || ".slide" in f.name) return@forEach
        val id = f.nameWithoutExtension.split(splitter).firstOrNull { it in ids } ?: return@forEach
        if (id in canonical) return@forEach
        val target = File(dir, "$id.$ext")
        if (!target.exists() && f.renameTo(target)) canonical[id] = target
    }
    return canonical.keys.filterTo(HashSet()) { it in ids }
}

/** Layout antigo (<usuário>/media/...) → novo (mídia na pasta do usuário, metadados em .dados). */
fun migrateOldLayout(dir: File): Boolean {
    val old = File(dir, "media")
    if (!old.isDirectory) return false
    val meta = metaDir(dir).apply { mkdirs() }
    old.listFiles().orEmpty().forEach { f ->
        val target = if (f.name.endsWith(".info.json") || f.name.contains(".thumb.")) File(meta, f.name) else File(dir, f.name)
        if (!target.exists()) f.renameTo(target) else f.delete()
    }
    File(dir, "archive.txt").takeIf { it.exists() }?.let { val t = File(meta, "archive.txt"); if (!t.exists()) it.renameTo(t) else it.delete() }
    old.deleteRecursively()
    return true
}

// ───────────── indexação ─────────────

/** Lê só o que ainda não está no banco (não reparseia milhares de .info.json a cada passada). */
fun scanNew(dir: File, pid: Long, known: MutableSet<String>): List<Post> {
    val media = finishedFiles(dir)
    val metaFiles = metaDir(dir).listFiles().orEmpty()
    val thumbs = HashMap<String, File>()
    metaFiles.forEach { f -> if (f.name.contains(".thumb.")) thumbs.putIfAbsent(f.name.substringBefore(".thumb."), f) }
    val out = mutableListOf<Post>()
    for (jf in metaFiles) {
        if (!jf.name.endsWith(".info.json")) continue
        val id = jf.name.removeSuffix(".info.json")
        val key = "$pid:$id"
        if (key in known) continue

        val slides = runCatching { slidesIndex(dir, id).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        val m = media[id]
        val mediaFile: String
        val isVideo: Boolean
        if (slides != null) {
            // post de fotos: mediaPath = música (se houver) ou a 1ª foto
            mediaFile = m?.takeIf { it.extension.lowercase() in AUDIO_EXTS }?.path ?: slides.substringBefore('\n')
            isVideo = false
        } else {
            if (m == null || m.extension.lowercase() in AUDIO_EXTS) continue   // incompleto: não aparece
            mediaFile = m.path
            isVideo = m.extension.lowercase() in VIDEO_EXTS
        }
        val j = runCatching { JSONObject(jf.readText()) }.getOrNull() ?: continue
        out += Post(
            key = key, profileId = pid,
            title = j.optString("title"), description = j.optString("description"),
            mediaPath = mediaFile,
            // capa: a baixada; senão a 1ª foto do post (vídeo de carrossel não serve de miniatura)
            thumbPath = thumbs[id]?.path ?: slides?.lines()?.firstOrNull { it.substringAfterLast('.').lowercase() !in VIDEO_EXTS },
            isVideo = isVideo,
            likes = j.longOrNull("like_count"), views = j.longOrNull("view_count"),
            timestamp = postTime(j) ?: jf.lastModified(),
            width = j.intOrNull("width"), height = j.intOrNull("height"),
            slides = slides
        )
        known += key
    }
    return out
}

/**
 * Data do post em ms. O YouTube muitas vezes só manda "upload_date" (AAAAMMDD), sem "timestamp":
 * sem isso a data e a ordem viravam a hora do download.
 */
private fun postTime(j: JSONObject): Long? =
    (j.longOrNull("timestamp") ?: j.longOrNull("release_timestamp"))?.times(1000)
        ?: j.str("upload_date")?.let { d ->
            runCatching {
                java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d)?.time
            }.getOrNull()
        }

/** Vídeos indexados por versões antigas não têm dimensões: lê do .info.json uma única vez. */
suspend fun backfillSizes(dao: AppDao, pid: Long, dir: File) {
    for (p in dao.videosWithoutSize(pid)) {
        val j = readInfo(dir, p.key.substringAfter(':'))
        dao.setSize(p.key, j?.intOrNull("width") ?: 0, j?.intOrNull("height") ?: 0)   // 0 = desconhecido, não tenta de novo
    }
}

// ───────────── perfis que já estão na pasta ─────────────

private val FOLDER_PLATFORM = mapOf("YouTube" to "youtube", "TikTok" to "tiktok", "Instagram" to "instagram", "X" to "x", "Reddit" to "reddit")

/** Link do perfil no mesmo formato que parseProfileUrl gera (é a chave do download no WorkManager). */
fun profileUrl(platform: String, username: String) = when (platform) {
    "youtube" -> if (username.startsWith("UC") && username.length == 24) "https://www.youtube.com/channel/$username/videos"
        else "https://www.youtube.com/@$username/videos"
    "tiktok" -> "https://www.tiktok.com/@$username"
    "instagram" -> "https://www.instagram.com/$username/"
    "reddit" -> if (Reddit.isSubreddit(username)) "https://www.reddit.com/r/${Reddit.nameOf(username)}/"
        else "https://www.reddit.com/user/${Reddit.nameOf(username)}/"
    else -> "https://x.com/$username"
}

/**
 * Procura perfis já baixados em <pasta>/<Aplicativo>/<usuário>/ (pastas com .dados) e coloca no app,
 * com as publicações que estiverem completas. Perfil que já existia no app passa a apontar para a pasta
 * encontrada. Retorna quantos perfis foram encontrados.
 */
/**
 * Nome, bio e seguidores do perfil gravados na própria pasta (.dados/perfil.json). O banco do app some
 * ao reinstalar; com isso, ao reimportar a pasta, o perfil volta com o nome certo em vez do usuário/id.
 */
fun saveProfileInfo(dir: File, p: Profile) = runCatching {
    File(metaDir(dir), "perfil.json").writeText(JSONObject().put("name", p.displayName).put("bio", p.bio)
        .putOpt("followers", p.followers).toString())
}

/** Nome/bio/seguidores salvos; sem perfil.json (pastas antigas), o nome vem do .info.json de um post. */
private fun readProfileInfo(dir: File, platform: String): Triple<String?, String?, Long?> {
    runCatching { JSONObject(File(metaDir(dir), "perfil.json").readText()) }.getOrNull()?.let { j ->
        return Triple(j.str("name"), j.str("bio"), j.longOrNull("followers"))
    }
    val info = metaDir(dir).listFiles().orEmpty().asSequence().filter { it.name.endsWith(".info.json") }.take(5)
        .mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
    // TikTok e YouTube: "channel" é o nome de exibição; Instagram/Reddit: pa_name do plugin; X: uploader
    val name = info.firstNotNullOfOrNull { j ->
        when (platform) {
            "instagram", "reddit" -> j.str("pa_name")
            "x" -> j.str("uploader")
            else -> j.str("channel")
        }
    }
    val bio = info.firstNotNullOfOrNull { it.str("pa_bio") }
    return Triple(name, bio, info.firstNotNullOfOrNull { it.longOrNull("pa_followers") ?: it.longOrNull("channel_follower_count") })
}

suspend fun importExisting(dao: AppDao, base: String): Int = withContext(Dispatchers.IO) {
    var found = 0
    for ((label, platform) in FOLDER_PLATFORM) {
        for (dir in File(base, label).listFiles().orEmpty()) {
            if (!dir.isDirectory || dir.name.startsWith(".")) continue
            if (!metaDir(dir).isDirectory && !File(dir, "media").isDirectory) continue   // não é pasta de perfil do app
            migrateOldLayout(dir); fixInfoNames(dir)
            val pfp = File(dir, "pfp.jpg").takeIf { it.isFile }?.path
            val cur = dao.find(platform, dir.name)
            val (name, bio, followers) = readProfileInfo(dir, platform)
            val pid = when {
                cur == null -> dao.upsert(Profile(platform = platform, username = dir.name, url = profileUrl(platform, dir.name),
                    displayName = name ?: dir.name, bio = bio.orEmpty(), followers = followers,
                    dir = dir.path, avatarPath = pfp, updatedAt = dir.lastModified()))
                // perfil importado antes desta correção: estava com o usuário/id no lugar do nome
                cur.displayName == cur.username && name != null && cur.dir == dir.path -> {
                    dao.upsert(cur.copy(displayName = name, bio = cur.bio.ifBlank { bio.orEmpty() }, followers = cur.followers ?: followers)); cur.id
                }
                cur.dir != dir.path -> {   // perfil veio de outra pasta: caminhos antigos não valem mais
                    dao.deletePosts(cur.id)
                    dao.upsert(cur.copy(dir = dir.path, avatarPath = pfp ?: cur.avatarPath)); cur.id
                }
                else -> cur.id
            }
            val posts = scanNew(dir, pid, dao.keys(pid).toMutableSet())
            if (posts.isNotEmpty()) dao.upsertPosts(posts)
            dao.recount(pid)
            found++
        }
    }
    found
}

/**
 * Remove do app os posts cujo arquivo não existe mais (pasta trocada, arquivo apagado pelo gerenciador
 * de arquivos…). Sem isso o perfil mostrava títulos com miniatura cinza e vídeo que não abre.
 * Se o id não estiver no archive.txt da pasta atual, a próxima verificação baixa de novo.
 */
suspend fun pruneMissing(dao: AppDao, pid: Long) = withContext(Dispatchers.IO) {
    val gone = dao.postsOnce(pid).filter { !File(it.mediaPath).exists() }
    gone.forEach { dao.deletePost(it.key) }
    if (gone.isNotEmpty()) dao.recount(pid)
    gone.size
}
