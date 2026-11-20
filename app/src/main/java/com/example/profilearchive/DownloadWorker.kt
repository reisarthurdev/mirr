package com.example.profilearchive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

fun workName(url: String) = "dl:$url"

fun enqueueDownload(ctx: Context, url: String, seed: String? = null): UUID {
    val prefs = Prefs(ctx)
    val req = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(workDataOf("url" to url, "seed" to seed))
        .addTag("dl").addTag("u:$url")
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
        )
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    WorkManager.getInstance(ctx).enqueueUniqueWork(workName(url), ExistingWorkPolicy.KEEP, req)
    return req.id
}

private fun errLine(e: Throwable) = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: "erro desconhecido"

/** Login do site (cookies.txt próprio deste processo, ver SiteCookies). */
private fun YoutubeDLRequest.withCookies(c: SiteCookies, procId: String) = apply {
    c.forProcess(procId)?.let { addOption("--cookies", it.path) }
}

/** Pega o secUid a partir de um vídeo do perfil (não depende da página do perfil, que o TikTok bloqueia). */
private fun secUidFromVideo(videoUrl: String, cookies: SiteCookies, procId: String): String? = runCatching {
    val r = YoutubeDLRequest(videoUrl).apply {
        addOption("--skip-download"); addOption("--no-playlist")
        addOption("--print", "%(channel_id)s")
    }.withCookies(cookies, procId)
    YoutubeDL.getInstance().execute(r, procId, null).out.lineSequence().map { it.trim() }.firstOrNull { SEC_UID.matches(it) }
}.getOrNull()

/** Aviso salvo no perfil: {"hint": "...", "items": [{"id": "...", "msg": "..."}]} */
private fun encodeFailures(items: Map<String, String>, hint: String?): String = JSONObject().apply {
    hint?.let { put("hint", it) }
    put("items", JSONArray().apply { items.forEach { (id, msg) -> put(JSONObject().put("id", id).put("msg", msg)) } })
}.toString()

/**
 * "No video formats found" no TikTok = o post veio sem os links de vídeo. O TikTok faz isso com posts
 * marcados como sensíveis/18+ quando a requisição não é de uma conta logada (com idade verificada).
 */
private fun loginHint(t: Target, errs: Collection<String>, loggedIn: Boolean): String? = when {
    errs.none { it.contains("No video formats", true) } ->
        if (errs.any { it.contains("login", true) || it.contains("log in", true) || it.contains("private", true) })
            "Algumas publicações exigem login: cole os cookies de uma conta logada nas Configurações." else null
    t.platform != "tiktok" -> null
    !loggedIn -> "O TikTok só entrega esses vídeos para uma conta logada (conteúdo marcado como sensível/18+). " +
        "Cole nas Configurações os cookies de uma conta logada no TikTok (precisa ter o \"sessionid\") e tente de novo."
    else -> "Mesmo logado o TikTok não entregou os vídeos. A conta dos cookies precisa ter idade confirmada (18+) " +
        "e conseguir abrir esses vídeos no navegador; se a sessão expirou, faça login de novo e cole os cookies novos."
}

// ───────────── listagem ─────────────

/** Item da listagem do perfil: entrada do yt-dlp (--flat-playlist) já com os extras do plugin. */
private class Entry(val id: String, val url: String, val raw: JSONObject) {
    val isPhotoPost get() = (raw.optJSONArray("slides")?.length() ?: 0) > 0
    val hasDirectLinks get() = (raw.optJSONArray("pa_formats")?.length() ?: 0) > 0
}

/** Lista o perfil inteiro de uma vez (rápido: não abre cada post). Uma linha JSON por post. */
private fun listProfile(listUrl: String, t: Target, cookies: SiteCookies, procId: String): List<Entry> {
    val r = YoutubeDLRequest(listUrl).apply {
        addOption("--flat-playlist"); addOption("--ignore-errors")
        addOption("--print", "%()j")
        addOption("--extractor-retries", "3"); addOption("--socket-timeout", "30")
    }.withCookies(cookies, procId)
    val out = YoutubeDL.getInstance().execute(r, procId, null).out
    return out.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.mapNotNull { l ->
        val j = runCatching { JSONObject(l) }.getOrNull() ?: return@mapNotNull null
        val id = j.str("id") ?: return@mapNotNull null
        // TikTok listado por secUid gera links "@<secUid>/video/…": monta o link canônico com o usuário
        val link = if (t.platform == "tiktok") "https://www.tiktok.com/@${t.username}/video/$id"
        else j.str("webpage_url") ?: j.str("url") ?: return@mapNotNull null
        Entry(id, link, j)
    }.filter { it.url.startsWith("http") }.distinctBy { it.id }.toList()
}

/**
 * Instagram: lista pela API do Instagram em HTTP direto (ver InstagramApi), que limita bem menos que o yt-dlp.
 * Retorna null só se algo inesperado falhar (o worker cai para o plugin do yt-dlp).
 */
private suspend fun listInstagram(ctx: Context, t: Target, onProgress: suspend (String) -> Unit): List<Entry>? {
    val posts = try {
        InstagramApi.create(ctx).listProfile(t.username, onProgress)
    } catch (e: InstagramApi.Stop) { throw e                  // resposta do Instagram: mostra o motivo
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { return null }
    onProgress("${posts.size} posts encontrados")
    return posts.map { Entry(it.getString("id"), it.getString("url"), it) }
}

/**
 * YouTube: a aba /videos não traz Shorts nem lives, e o link do canal sem aba devolve as abas em vez dos
 * vídeos. Lista cada aba e junta; aba que o canal não tem é ignorada (só falha se nenhuma funcionar).
 */
private fun listYouTube(t: Target, cookies: SiteCookies, procFor: (String) -> String): List<Entry> {
    val base = t.url.removeSuffix("/").removeSuffix("/videos")
    var lastError: Exception? = null
    val all = listOf("videos", "shorts", "streams").flatMap { tab ->
        try { listProfile("$base/$tab", t, cookies, procFor(tab)) } catch (e: Exception) { lastError = e; emptyList() }
    }.distinctBy { it.id }
    if (all.isEmpty()) lastError?.let { throw it }
    return all
}

/**
 * Música de posts de fotos que ficaram sem ela (o link não veio na listagem ou falhou): o yt-dlp
 * baixa o áudio do post (para posts de fotos ele só tem o áudio mesmo).
 */
private fun fetchSlideMusic(urls: List<String>, dir: File, cookies: SiteCookies, procId: String) {
    if (urls.isEmpty()) return
    val r = YoutubeDLRequest(urls).apply {
        addOption("-o", "${dir.path}/%(id)s.%(ext)s")
        addOption("-f", "ba/b"); addOption("--ignore-errors"); addOption("--no-overwrites")
        addOption("--extractor-retries", "3"); addOption("--socket-timeout", "30")
    }.withCookies(cookies, procId)
    runCatching { YoutubeDL.getInstance().execute(r, procId, null) }
}

// ───────────── download pelo yt-dlp ─────────────

private val ITEM_ERROR = Regex("""ERROR: \[[^\]]+] ([\w-]+): (.+)""")

/** Tira do erro do yt-dlp o pedido genérico de "reporte o problema / atualize". */
private fun cleanError(msg: String) = msg
    .replace(Regex(""";?\s*please report this issue on.*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*Confirm you are on the latest version.*$""", RegexOption.IGNORE_CASE), "")
    .trim().trimEnd(';', ',').ifBlank { "erro desconhecido" }

/**
 * Converte entradas da listagem em infos prontas para o yt-dlp baixar direto (--load-info-json):
 * os links de vídeo vieram na própria listagem (pa_formats), então nenhuma página de vídeo é aberta.
 * Isso pega posts cuja página vem sem links (ex.: marcados como sensíveis) e é mais rápido.
 * Se um link falhar, o próprio yt-dlp cai para o caminho normal pelo webpage_url.
 */
private fun directInfos(items: List<Entry>, extractor: String): JSONArray = JSONArray().apply {
    items.forEach { e ->
        val j = JSONObject(e.raw.toString())
        listOf("url", "ie_key", "slides", "slides_audio", "pa_avatar").forEach { j.remove(it) }
        // extractor_key define a linha do archive.txt ("tiktok <id>", "instagram <id>")
        j.put("_type", "video").put("extractor", extractor).put("extractor_key", extractor)
            .put("webpage_url", e.url).put("original_url", e.url)
            .put("formats", j.remove("pa_formats"))
        put(j)
    }
}

/**
 * Baixa um pequeno lote num único processo do yt-dlp (pelos links do post, ou direto pelos links da
 * listagem quando [direct]). Com --ignore-errors o lote continua se um item falhar; o erro de cada item
 * (linhas "ERROR: [TikTok] <id>: …") vai para [errors]. Retorna a última linha de erro.
 */
private fun runBatch(
    batch: List<Entry>, direct: Boolean, dir: File, tmp: File, cookies: SiteCookies, procId: String, t: Target,
    errors: MutableMap<String, String>, onLine: (String) -> Unit
): String? {
    val meta = metaDir(dir)
    val infoList = if (direct) File(tmp, "li-$procId.json").apply { writeText(directInfos(batch, t.label).toString()) } else null
    val req = YoutubeDLRequest(if (direct) emptyList() else batch.map { it.url }).apply {
        infoList?.let { addOption("--load-info-json", it.path) }
        addOption("-o", "${dir.path}/%(id)s.%(ext)s")
        addOption("-o", "infojson:${meta.path}/%(id)s")            // o yt-dlp acrescenta ".info.json"
        addOption("-o", "thumbnail:${meta.path}/%(id)s.thumb.%(ext)s")
        addOption("--write-info-json"); addOption("--write-thumbnail")
        addOption("--download-archive", File(meta, "archive.txt").path)
        addOption("--ignore-errors"); addOption("--no-overwrites"); addOption("--no-mtime")
        // Qualidade máxima que o aparelho decodifica em hardware (ver DeviceVideo).
        // Sem forçar mp4: VP9/AV1 + Opus vão para .mkv sem reencodar; H.264/H.265 + AAC ficam em .mp4.
        addOption("-f", "bv*+ba/b")
        addOption("-S", DeviceVideo.sort)
        addOption("--merge-output-format", "mp4/mkv")
        addOption("--concurrent-fragments", "4"); addOption("--http-chunk-size", "10M")
        addOption("--retries", "5"); addOption("--fragment-retries", "5")
        addOption("--extractor-retries", "3"); addOption("--socket-timeout", "30")
        // Instagram e X limitam rápido (429): pausa entre requisições e entre posts
        if (t.platform == "instagram" || t.platform == "x" || t.platform == "reddit") {
            addOption("--sleep-requests", "1.5"); addOption("--sleep-interval", "2"); addOption("--max-sleep-interval", "5")
        }
    }.withCookies(cookies, procId)
    return try {
        YoutubeDL.getInstance().execute(req, procId, { _, _, line -> onLine(line) }); null
    } catch (e: Exception) {
        e.message.orEmpty().lineSequence().forEach { l ->
            ITEM_ERROR.find(l)?.let { m -> errors[m.groupValues[1]] = cleanError(m.groupValues[2]) }
        }
        errLine(e)
    } finally { infoList?.delete() }
}

/**
 * Baixa só os metadados (.info.json e, se pedido, a miniatura). Com --ignore-no-formats-error o
 * yt-dlp grava o info mesmo quando o post não tem vídeo: é assim que posts de fotos que davam
 * "No video formats found" revelam as fotos (campo "slides" do plugin).
 */
private fun refreshInfo(urls: List<String>, dir: File, cookies: SiteCookies, procId: String, thumbs: Boolean = false) {
    if (urls.isEmpty()) return
    val r = YoutubeDLRequest(urls).apply {
        addOption("--skip-download"); addOption("--write-info-json"); addOption("--ignore-errors")
        addOption("--ignore-no-formats-error")
        addOption("-o", "infojson:${metaDir(dir).path}/%(id)s")
        if (thumbs) { addOption("--write-thumbnail"); addOption("-o", "thumbnail:${metaDir(dir).path}/%(id)s.thumb.%(ext)s") }
        addOption("--extractor-retries", "3"); addOption("--socket-timeout", "30")
    }.withCookies(cookies, procId)
    runCatching { YoutubeDL.getInstance().execute(r, procId, null) }
}

// ───────────── worker ─────────────

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val urlArg get() = inputData.getString("url") ?: ""
    private val nid get() = 1000 + (urlArg.hashCode() and 0x3FFF)

    override suspend fun getForegroundInfo() = fgInfo(applicationContext.getString(R.string.app_name), "Preparando download…", null)

    private fun fgInfo(title: String, text: String, pct: Int?): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val b = NotificationCompat.Builder(applicationContext, "dl")
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancel)
        if (pct != null) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        val n = b.build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(nid, n)
    }

    private fun fail(msg: String) = Result.failure(workDataOf("error" to msg))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val t = parseProfileUrl(urlArg) ?: return@withContext fail("Link não reconhecido")
        val seed = inputData.getString("seed")
        val app = applicationContext
        val dao = Db.get(app).dao()
        var createdId: Long? = null
        var ok = false
        var acquired = false
        var cookies = SiteCookies(app, "none", "")   // trocado pelo do site logo abaixo
        val procBase = id.toString()
        val procIds = ConcurrentLinkedQueue<String>()
        val lastLine = AtomicReference("")
        val lastErr = AtomicReference<String?>(null)
        val itemErrors = ConcurrentHashMap<String, String>()   // id → erro
        // cancelamento: encerra todos os processos do yt-dlp
        coroutineContext[Job]?.invokeOnCompletion {
            if (it != null) procIds.forEach { p -> runCatching { YoutubeDL.getInstance().destroyProcessById(p) } }
        }
        fun proc(name: String) = "$procBase-$name".also { procIds += it }
        val title = if (t.platform == "reddit") Reddit.handle(t.username) else "@${t.username}"
        try {
            runCatching { setForeground(fgInfo(title, "Preparando…", null)) }   // sem notificação não pode impedir o download
            setProgress(workDataOf("line" to "Iniciando…"))
            Engine.acquire(app); acquired = true
            val prefs = Prefs(app)
            cookies = SiteCookies(app, t.platform, procBase)
            val base = prefs.baseDir
            if (!hasStorageAccess(app, base))
                return@withContext fail("Sem permissão para gravar em $base. Conceda 'acesso a todos os arquivos' nas Configurações.")

            val safeName = t.username.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = profileDir(base, t.label, safeName)
            val meta = metaDir(dir)
            if (!(meta.mkdirs() || meta.isDirectory)) return@withContext fail("Não foi possível criar ${dir.path}")
            val migrated = migrateOldLayout(dir)
            fixInfoNames(dir)
            dir.listFiles().orEmpty().filter { ".part." in it.name }.forEach { it.delete() }   // sobras de execução interrompida

            val old = dao.find(t.platform, t.username)
            if (old == null) // linha provisória: o perfil já aparece na tela inicial com o progresso
                createdId = dao.upsert(Profile(platform = t.platform, username = t.username, url = t.url,
                    displayName = t.username, dir = dir.path, updatedAt = System.currentTimeMillis()))
            val pid = old?.id ?: createdId!!
            // perfil que estava em outra pasta: passa a usar a atual e esquece posts cujo arquivo sumiu
            if (old != null && old.dir != dir.path) dao.upsert(old.copy(dir = dir.path))
            pruneMissing(dao, pid)

            // o que já está no aparelho aparece no perfil desde já
            val known = (if (migrated) emptySet() else dao.keys(pid).toSet()).toMutableSet()
            suspend fun indexNow() {
                val fresh = scanNew(dir, pid, known)
                if (fresh.isNotEmpty()) { dao.upsertPosts(fresh); dao.recount(pid) }
            }
            indexNow()

            // TikTok: com o secUid conhecido a listagem não depende da página do perfil (que o TikTok bloqueia)
            var secUid = if (t.platform == "tiktok") cachedSecUid(dir) else null
            if (t.platform == "tiktok" && secUid == null && seed != null) {
                setProgress(workDataOf("line" to "Identificando o perfil pelo vídeo…"))
                secUid = secUidFromVideo(seed, cookies, proc("seed"))?.also { saveSecUid(dir, it) }
            }
            fun listUrl() = secUid?.let { "tiktokuser:$it" } ?: t.url

            // 1) dados do perfil + foto (a foto entra no app assim que chega)
            setProgress(workDataOf("line" to "Lendo perfil…"))
            var metaJson: JSONObject? = null
            // Instagram: pula (o plugin manda os dados do perfil em cada post da listagem). Cada consulta
            // a mais conta para o limite do Instagram, e consultas seguidas dão erro 429.
            // Reddit também pula: o yt-dlp não lê perfis do Reddit; os dados vêm junto da listagem
            for (a in 1..(if (t.platform == "instagram" || t.platform == "reddit") 0 else 2)) {
                if (metaJson != null) break
                runCatching {
                    val pm = proc("meta$a")
                    val r = YoutubeDLRequest(listUrl()).apply {
                        addOption("--flat-playlist"); addOption("--dump-single-json"); addOption("--playlist-items", "1")
                    }.withCookies(cookies, pm)
                    metaJson = JSONObject(YoutubeDL.getInstance().execute(r, pm, null).out)
                }
            }
            val mj = metaJson
            val first = mj?.optJSONArray("entries")?.optJSONObject(0)
            if (t.platform == "tiktok" && secUid == null)
                first?.str("channel_id")?.takeIf { SEC_UID.matches(it) }?.let { secUid = it; saveSecUid(dir, it) }
            val avatarFile = File(dir, "pfp.jpg")
            val avatarUrl = first?.str("pa_avatar") ?: mj?.optJSONArray("thumbnails")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("id").contains("avatar") }?.str("url")
            }
            val gotAvatar = avatarUrl != null && downloadAvatar(avatarUrl, avatarFile)
            val cur0 = dao.find(t.platform, t.username) ?: return@withContext Result.failure()
            dao.upsert(cur0.copy(
                displayName = (mj?.str("uploader") ?: mj?.str("channel") ?: first?.str("channel")
                    ?: mj?.str("title")?.takeUnless { SEC_UID.matches(it) }) ?: cur0.displayName,
                bio = mj?.str("description") ?: first?.str("pa_bio") ?: cur0.bio,
                avatarPath = if (gotAvatar || avatarFile.exists()) avatarFile.path else cur0.avatarPath,
                followers = mj?.longOrNull("channel_follower_count") ?: first?.longOrNull("pa_followers") ?: cur0.followers
            ))

            // 2) lista TUDO o que o perfil tem (com tentativas)
            setProgress(workDataOf("line" to "Listando conteúdo…"))
            runCatching { setForeground(fgInfo(title, "Listando conteúdo…", null)) }
            var listing: List<Entry> = emptyList()
            var listErr: String? = null
            for (attempt in 1..3) {
                ensureActive()
                try {
                    listing = when (t.platform) {
                        "instagram" -> listInstagram(app, t) { msg -> setProgress(workDataOf("line" to msg)) }
                            ?: listProfile(listUrl(), t, cookies, proc("list$attempt"))   // WebView indisponível: plugin do yt-dlp
                        "youtube" -> listYouTube(t, cookies) { tab -> proc("list$attempt-$tab") }
                        "reddit" -> Reddit.list(app, t.username) { msg -> setProgressAsync(workDataOf("line" to msg)) }
                            .map { Entry(it.getString("id"), it.getString("url"), it) }
                        else -> listProfile(listUrl(), t, cookies, proc("list$attempt"))
                    }
                } catch (e: InstagramApi.Stop) {
                    listErr = e.message; break
                } catch (e: Reddit.Failed) {
                    listErr = e.message; break                  // resposta definitiva do Reddit (já esperou o limite dentro)                  // já esperou dentro (ou é definitivo); insistir só piora o bloqueio
                } catch (e: Exception) { if (e is CancellationException) throw e; listErr = errLine(e) }
                if (listing.isNotEmpty()) break
                // 429 = o site pediu para esperar: tentar de novo em 2 s só piora; espera de verdade
                val limited = listErr?.contains("429") == true
                if (attempt < 3) {
                    if (limited) setProgress(workDataOf("line" to "${t.label} limitou as consultas, tentando de novo em ${45 * attempt} s…"))
                    delay(if (limited) 45_000L * attempt else 2000L * attempt)
                }
            }
            if (listing.isEmpty()) {
                val secIdHint = if (t.platform == "tiktok" && listErr?.contains("secondary user ID", true) == true)
                    "O TikTok bloqueou a página do perfil. Em Adicionar, cole o link de UM VÍDEO desse perfil: o app identifica a conta por ele.\n\n"
                else ""
                return@withContext fail(
                    "Não foi possível listar o conteúdo (${listErr ?: "nenhum item encontrado"}).\n\n$secIdHint" +
                        "Instagram/X/TikTok normalmente exigem login: cole seus cookies nas Configurações. Tente novamente em instantes."
                )
            }
            // bio/seguidores que o passo 1 não trouxe: pega do 1º post da listagem
            listing.firstOrNull()?.raw?.let { e ->
                dao.find(t.platform, t.username)?.let { c ->
                    val bio = c.bio.ifBlank { e.str("pa_bio").orEmpty() }
                    val fol = c.followers ?: e.longOrNull("pa_followers")
                    val name = if (c.displayName == c.username) e.str("pa_name") ?: c.displayName else c.displayName
                    if (bio != c.bio || fol != c.followers || name != c.displayName)
                        dao.upsert(c.copy(bio = bio, followers = fol, displayName = name))
                }
            }
            // sem foto de perfil pelo passo 1: tenta pela listagem
            if (!avatarFile.exists()) listing.firstNotNullOfOrNull { it.raw.str("pa_avatar") }?.let { u ->
                if (downloadAvatar(u, avatarFile)) dao.find(t.platform, t.username)?.let { dao.upsert(it.copy(avatarPath = avatarFile.path)) }
            }

            // 3) aproveita o que já está no aparelho: arquivo com o id no nome é renomeado e
            //    mídia sem metadados ganha só o .info.json (sem baixar o vídeo de novo)
            setProgress(workDataOf("line" to "Verificando o que já está no aparelho…"))
            val byId = listing.associateBy { it.id }
            // posts baixados antes do disco de música: completa o .info.json com a música vinda da listagem
            if (t.platform == "tiktok" || t.platform == "instagram")
                runCatching { addMissingMusic(dir, listing.mapNotNull { e -> e.raw.optJSONObject("pa_music")?.let { e.id to it } }) }
            val onDevice = adoptRenamed(dir, byId.keys)
            onDevice.filter { !infoFile(dir, it).isFile }.chunked(25).forEach { chunk ->
                refreshInfo(chunk.mapNotNull { byId[it]?.url }, dir, cookies, proc("adopt"), thumbs = true)
            }
            indexNow()

            // concluído = post completo no aparelho, ou registrado no archive (inclui o que o usuário apagou
            // de propósito), exceto música solta de post de fotos, que não conta como baixado
            fun doneIds(): Set<String> {
                val audioOnly = finishedFiles(dir).filter { (id, f) -> f.extension.lowercase() in AUDIO_EXTS && !slidesIndex(dir, id).isFile }.keys
                return completedIds(dir) + (archiveIds(dir) - audioOnly)
            }
            val done0 = doneIds()
            val missingIds = listing.map { it.id }.filterTo(LinkedHashSet()) { it !in done0 }
            val total = missingIds.size
            fun stillMissing(): List<Entry> { val d = doneIds(); return listing.filter { it.id in missingIds && it.id !in d } }

            suspend fun publish() {
                val d = doneIds()
                val doneNow = missingIds.count { it in d }
                val pct = if (total > 0) doneNow * 100 / total else 100
                setProgress(workDataOf("d" to doneNow, "n" to total, "line" to lastLine.get().take(120)))
                runCatching { setForegroundAsync(fgInfo(title, "Baixando $doneNow de $total · $pct%", pct)) }
            }

            if (missingIds.isNotEmpty()) {
                val par = (if (t.platform == "instagram" || t.platform == "x" || t.platform == "reddit") minOf(prefs.parallel, 2) else prefs.parallel).coerceIn(1, MAX_PARALLEL)
                val photoGate = Semaphore(MAX_PARALLEL)

                /** Posts de fotos: o app baixa direto (tudo ou nada). */
                suspend fun downloadPhotos(items: List<Pair<String, JSONObject>>) = coroutineScope {
                    items.map { (id, info) ->
                        launch {
                            photoGate.withPermit {
                                lastLine.set("Fotos do post $id")
                                val err = downloadPhotoPost(dir, id, info)
                                if (err != null) itemErrors[id] = err else itemErrors.remove(id)
                            }
                        }
                    }.joinAll()
                }

                /** Vídeos pelo yt-dlp, vários processos ao mesmo tempo. */
                suspend fun parallelDownload(items: List<Entry>, direct: Boolean, round: Int) = coroutineScope {
                    if (items.isEmpty()) return@coroutineScope
                    val chunk = (items.size / (par * 2)).coerceIn(1, if (direct) 10 else 5)
                    val queue = ConcurrentLinkedQueue(items.chunked(chunk))
                    (0 until par).map { w ->
                        launch {
                            val ps = proc("r$round${if (direct) "d" else ""}-w$w")
                            while (isActive) {
                                val b = queue.poll() ?: break
                                runBatch(b, direct, dir, app.cacheDir, cookies, ps, t, itemErrors) { line -> lastLine.set(line) }
                                    ?.let { lastErr.set(it) }
                            }
                        }
                    }.joinAll()
                }

                publish()
                coroutineScope {
                    val monitor = launch {
                        var tick = 0
                        while (isActive) {
                            delay(1000); tick++
                            if (tick % 4 == 0) indexNow()
                            publish()
                        }
                    }
                    // a) posts de fotos identificados na listagem
                    downloadPhotos(stillMissing().filter { it.isPhotoPost }.map { it.id to it.raw })
                    // b) TikTok e Instagram: vídeos com links já na listagem baixam direto, sem abrir uma página
                    //    por vídeo (mais rápido e, no Instagram, é o que evita o bloqueio 429)
                    if (t.platform == "tiktok" || t.platform == "instagram")
                        parallelDownload(stillMissing().filter { !it.isPhotoPost && it.hasDirectLinks }, direct = true, round = 0)
                    // c) o resto (e o que falhou acima) pelo caminho normal, com novas tentativas
                    // (Instagram: 1 rodada só; cada página de post conta para o limite de consultas)
                    for (round in 1..(if (t.platform == "instagram") 1 else 3)) {
                        val rest = stillMissing().filter { !it.isPhotoPost }
                        if (rest.isEmpty()) break
                        if (round > 1) delay(3000)
                        parallelDownload(rest, direct = false, round = round)
                    }
                    // d) outros sites: o que o caminho normal não conseguiu (ex.: Instagram pedindo login) tenta pelos links da listagem
                    if (t.platform != "tiktok" && t.platform != "instagram")
                        parallelDownload(stillMissing().filter { !it.isPhotoPost && it.hasDirectLinks }, direct = true, round = 9)
                    // d) TikTok: o que veio sem vídeo ou só com a música é post de fotos. Rebaixa os metadados
                    //    (o plugin acrescenta as fotos) e baixa as fotos + música juntas
                    if (t.platform == "tiktok") {
                        val audioOnly = finishedFiles(dir).filter { (id, f) -> f.extension.lowercase() in AUDIO_EXTS && !slidesIndex(dir, id).isFile }.keys
                        val retry = stillMissing().map { it.id }.toSet() + audioOnly
                        if (retry.isNotEmpty()) {
                            lastLine.set("Procurando fotos dos posts que faltaram…")
                            retry.chunked(25).forEach { c -> refreshInfo(c.map { byId[it]?.url ?: "https://www.tiktok.com/@${t.username}/video/$it" }, dir, cookies, proc("refresh")) }
                            downloadPhotos(retry.mapNotNull { id -> readInfo(dir, id)?.takeIf { (it.optJSONArray("slides")?.length() ?: 0) > 0 }?.let { id to it } })
                        }
                    }
                    monitor.cancelAndJoin()
                }
            }
            // músicas dos slides que ficaram sem: busca pelo yt-dlp e liga ao post (que passa a tocar a música)
            if (t.platform == "tiktok") {
                val media = finishedFiles(dir)
                val silent = completedSlideIds(dir).filter { id ->
                    media[id]?.extension?.lowercase() !in AUDIO_EXTS && !File(metaDir(dir), "$id.nomusic").exists()
                }
                if (silent.isNotEmpty()) {
                    setProgress(workDataOf("line" to "Baixando as músicas dos slides…"))
                    silent.chunked(20).forEach { c ->
                        fetchSlideMusic(c.map { byId[it]?.url ?: "https://www.tiktok.com/@${t.username}/video/$it" }, dir, cookies, proc("music"))
                    }
                    val after = finishedFiles(dir)
                    silent.forEach { id ->
                        val audio = after[id]?.takeIf { it.extension.lowercase() in AUDIO_EXTS }
                        if (audio != null) dao.setMedia("$pid:$id", audio.path)
                        else runCatching { File(metaDir(dir), "$id.nomusic").writeText("") }   // post sem música: não tenta de novo
                    }
                }
            }
            // música solta (fotos não vieram) é apagada e sai do archive: o post inteiro volta na próxima vez
            if (t.platform == "tiktok") dropOrphanAudio(dir).forEach { id ->
                itemErrors.putIfAbsent(id, "as fotos do post não puderam ser baixadas")
            }
            // Instagram: posts de fotos baixados antes sem a música escolhida pelo autor ganham o áudio agora
            if (t.platform == "instagram") {
                val media = finishedFiles(dir)
                listing.filter { e -> e.raw.str("slides_audio") != null && slidesIndex(dir, e.id).isFile &&
                        media[e.id]?.extension?.lowercase() !in AUDIO_EXTS }
                    .forEach { e ->
                        setProgress(workDataOf("line" to "Baixando músicas dos posts…"))
                        runCatching { downloadPostAudio(dir, e.id, e.raw.str("slides_audio")!!) }.getOrNull()
                            ?.let { dao.setMedia("$pid:${e.id}", it.path) }
                    }
            }
            // capas das músicas (disco no player); falha aqui não afeta o download
            if (t.platform == "tiktok") runCatching { downloadMusicCovers(dir) }
            // Reddit: comentários dos posts que estão no aparelho (falha não derruba o download)
            if (t.platform == "reddit") {
                indexNow()
                val have = completedIds(dir) + completedSlideIds(dir)
                runCatching {
                    Reddit.fetchComments(app, dir, listing.filter { it.id in have }.map { it.id to (it.raw.longOrNull("timestamp") ?: 0L) }) { msg ->
                        setProgressAsync(workDataOf("line" to msg))
                        runCatching { setForegroundAsync(fgInfo(title, msg, null)) }
                    }
                }
            }

            // 4) finaliza
            indexNow()
            backfillSizes(dao, pid, dir)
            dao.recount(pid)
            runCatching {
                MediaScannerConnection.scanFile(app,
                    (listOf(dir.path) + dir.listFiles().orEmpty().filter { it.isFile }.map { it.path }).toTypedArray(), null, null)
            }
            // O download da conta termina sempre: o que falhou vira um aviso (⚠️) no card do perfil
            val doneEnd = doneIds()
            val failures = LinkedHashMap<String, String>()
            listing.forEach { e ->
                if (e.id !in doneEnd) failures[e.id] = itemErrors[e.id] ?: lastErr.get()?.let(::cleanError) ?: "não foi baixado (sem detalhes)"
            }
            // posts que o site nem listou (Instagram sem login mostra só os mais recentes)
            val totalOnSite = mj?.intOrNull("pa_total") ?: listing.firstOrNull()?.raw?.intOrNull("pa_total") ?: 0
            val unlisted = (totalOnSite - listing.size).coerceAtLeast(0)
            if (unlisted > 0) failures["+$unlisted"] =
                "não listados: sem login o ${t.label} só mostra os posts mais recentes (cole os cookies para baixar todos)"
            val cur = dao.find(t.platform, t.username) ?: return@withContext Result.failure()
            saveProfileInfo(dir, cur)   // nome/bio na pasta: sobrevivem a reinstalar o app
            dao.upsert(cur.copy(
                avatarPath = cur.avatarPath ?: meta.listFiles().orEmpty().firstOrNull { it.name.contains(".thumb.") }?.path,
                synced = failures.isEmpty(), updatedAt = System.currentTimeMillis(),
                failedCount = failures.size - (if (unlisted > 0) 1 else 0) + unlisted,
                failInfo = if (failures.isEmpty()) null else encodeFailures(failures, loginHint(t, failures.values, cookies.loggedIn))
            ))
            ok = true
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(e.message ?: "Erro desconhecido")
        } finally {
            if (acquired) Engine.release()
            cookies.cleanup()
            // download novo que falhou/foi cancelado sem nada baixado: remove a linha provisória
            val c = createdId
            if (!ok && c != null) withContext(NonCancellable) {
                if ((dao.find(t.platform, t.username)?.postCount ?: 0) == 0) dao.deleteProfile(c)
            }
        }
    }
}
