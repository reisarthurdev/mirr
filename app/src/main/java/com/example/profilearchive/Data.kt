package com.example.profilearchive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,          // youtube | tiktok | instagram | x
    val username: String,
    val url: String,
    val displayName: String,
    val bio: String = "",
    val avatarPath: String? = null,
    val followers: Long? = null,
    val postCount: Int = 0,
    val dir: String,
    val updatedAt: Long = 0,
    val synced: Boolean = false,   // true = última passada completou sem erro (permite atualização rápida)
    @ColumnInfo(defaultValue = "0")
    val failedCount: Int = 0,      // publicações que não puderam ser baixadas na última atualização (⚠️ no card)
    val failInfo: String? = null   // JSON com o erro de cada uma (ver failures())
)

data class Failures(val hint: String?, val items: List<Pair<String, String>>)   // id → erro

/** Lê o aviso salvo pelo download. */
fun Profile.failures(): Failures? = failInfo?.let { raw ->
    runCatching {
        val j = JSONObject(raw)
        val arr = j.getJSONArray("items")
        Failures(
            j.optString("hint").takeIf { it.isNotBlank() },
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.optString("id") to o.optString("msg") } }
        )
    }.getOrNull()
}

@Entity(tableName = "posts", indices = [Index("profileId")])
data class Post(
    @PrimaryKey val key: String,
    val profileId: Long,
    val title: String,
    val description: String,
    val mediaPath: String,
    val thumbPath: String?,
    val isVideo: Boolean,
    val likes: Long?,
    val views: Long?,
    val timestamp: Long,
    val width: Int? = null,        // dimensões do vídeo (decidem preencher/encaixar no feed)
    val height: Int? = null,
    val slides: String? = null     // post de fotos: caminhos das imagens separados por \n (mediaPath = áudio)
)

val Post.isSlideshow: Boolean get() = !slides.isNullOrEmpty()
/** Tem algo para o player tocar: o vídeo ou a música do post de fotos (post de fotos sem música não toca nada). */
val Post.playable: Boolean get() = isVideo || (isSlideshow && mediaPath.substringAfterLast('.').lowercase() in AUDIO_EXTS)
fun Post.slideList(): List<String> = slides?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

@Dao
abstract class AppDao {
    @Query("SELECT * FROM profiles ORDER BY updatedAt DESC") abstract fun profiles(): Flow<List<Profile>>
    @Query("SELECT * FROM profiles WHERE id = :id") abstract fun profile(id: Long): Flow<Profile?>
    @Query("SELECT * FROM profiles WHERE platform = :p AND username = :u LIMIT 1") abstract suspend fun find(p: String, u: String): Profile?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsert(p: Profile): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertPosts(l: List<Post>)
    @Query("SELECT * FROM posts WHERE profileId = :id ORDER BY timestamp DESC") abstract fun posts(id: Long): Flow<List<Post>>
    @Query("SELECT * FROM posts WHERE `key` = :k") abstract fun post(k: String): Flow<Post?>
    @Query("SELECT `key` FROM posts WHERE profileId = :id") abstract suspend fun keys(id: Long): List<String>
    @Query("SELECT * FROM posts WHERE profileId = :id") abstract suspend fun postsOnce(id: Long): List<Post>
    @Query("UPDATE posts SET mediaPath = :path WHERE `key` = :k") abstract suspend fun setMedia(k: String, path: String)
    @Query("DELETE FROM posts WHERE profileId = :id") abstract suspend fun deletePosts(id: Long)
    @Query("DELETE FROM posts WHERE `key` = :k") abstract suspend fun deletePost(k: String)
    @Query("DELETE FROM profiles WHERE id = :id") abstract suspend fun deleteRow(id: Long)
    @Query("UPDATE profiles SET postCount = (SELECT COUNT(*) FROM posts WHERE profileId = :id) WHERE id = :id") abstract suspend fun recount(id: Long)
    @Query("UPDATE profiles SET synced = :s WHERE id = :id") abstract suspend fun setSynced(id: Long, s: Boolean)
    @Query("UPDATE profiles SET failedCount = 0, failInfo = NULL WHERE id = :id") abstract suspend fun clearFailures(id: Long)
    @Query("SELECT * FROM posts WHERE profileId = :id AND isVideo = 1 AND width IS NULL") abstract suspend fun videosWithoutSize(id: Long): List<Post>
    @Query("UPDATE posts SET width = :w, height = :h WHERE `key` = :k") abstract suspend fun setSize(k: String, w: Int, h: Int)

    @Transaction
    open suspend fun deleteProfile(id: Long) { deletePosts(id); deleteRow(id) }
}

@Database(entities = [Profile::class, Post::class], version = 4, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        /** v2 → v3: dimensões do vídeo + fotos dos slides. Migração real: não apaga perfis já baixados. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE posts ADD COLUMN width INTEGER")
                db.execSQL("ALTER TABLE posts ADD COLUMN height INTEGER")
                db.execSQL("ALTER TABLE posts ADD COLUMN slides TEXT")
            }
        }

        /** v3 → v4: aviso de publicações que falharam. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN failedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN failInfo TEXT")
            }
        }

        @Volatile private var i: Db? = null
        fun get(c: Context): Db = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "archive.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build().also { i = it }
        }
    }
}

/**
 * Dados da tela inicial mantidos vivos enquanto o app roda. A Navigation descarta a composição da
 * tela inicial ao abrir um perfil; ao voltar, um Flow novo começava vazio por um frame (mostrava
 * "Nenhum perfil" e os cards entravam com fade): era o flicker. Com StateFlow a lista está pronta no 1º frame.
 */
object HomeData {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var profilesFlow: StateFlow<List<Profile>?>? = null
    @Volatile private var worksFlow: StateFlow<List<WorkInfo>>? = null

    /** null = ainda carregando (diferente de "nenhum perfil") */
    fun profiles(ctx: Context): StateFlow<List<Profile>?> = profilesFlow ?: synchronized(this) {
        profilesFlow ?: Db.get(ctx).dao().profiles().stateIn(scope, SharingStarted.Eagerly, null).also { profilesFlow = it }
    }

    fun works(ctx: Context): StateFlow<List<WorkInfo>> = worksFlow ?: synchronized(this) {
        worksFlow ?: WorkManager.getInstance(ctx.applicationContext).getWorkInfosByTagFlow("dl")
            .stateIn(scope, SharingStarted.Eagerly, emptyList()).also { worksFlow = it }
    }
}

/**
 * Mesmo motivo do [HomeData], para a tela do perfil e o feed: ao voltar de um vídeo o perfil já tem os
 * posts no 1º frame. Sem isso a grade começava vazia, perdia a posição de rolagem e voltava ao topo.
 * Fica vivo 1 min depois de sair da tela; o último valor continua guardado para a próxima abertura.
 */
object ProfileData {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val profiles = java.util.concurrent.ConcurrentHashMap<Long, StateFlow<Profile?>>()
    private val posts = java.util.concurrent.ConcurrentHashMap<Long, StateFlow<List<Post>?>>()

    fun profile(ctx: Context, id: Long): StateFlow<Profile?> = profiles.getOrPut(id) {
        Db.get(ctx).dao().profile(id).stateIn(scope, SharingStarted.WhileSubscribed(60_000), null)
    }

    /** null = ainda carregando */
    fun posts(ctx: Context, id: Long): StateFlow<List<Post>?> = posts.getOrPut(id) {
        Db.get(ctx).dao().posts(id).stateIn(scope, SharingStarted.WhileSubscribed(60_000), null)
    }
}

/**
 * Downloads simultâneos (processos do yt-dlp) permitidos. 8 é o limite razoável: cada processo é um
 * python de ~40 MB e, acima disso, o TikTok/YouTube começam a recusar requisições por excesso.
 * Instagram e X continuam limitados a 2 no worker (bloqueiam a conta muito mais rápido).
 */
const val MAX_PARALLEL = 8

class Prefs(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Pasta padrão: /storage/emulated/0/Documentos/mirr */
    val defaultDir: String = File(Environment.getExternalStorageDirectory(), "Documentos/mirr").path

    /**
     * Pasta de downloads: a escolhida pelo usuário ou a padrão.
     * Quem usava a pasta das versões antigas continua nela (ver [migrateLegacyDir]).
     */
    var baseDir: String
        get() = sp.getString("base", null) ?: defaultDir
        set(v) { sp.edit().putString("base", v).apply() }
    /** Pasta em que a busca por perfis já baixados foi feita (para não repetir a cada abertura). */
    var importedDir: String?
        // chave nova: faz a busca rodar mais uma vez nas instalações atuais, para recuperar os nomes dos perfis
        get() = sp.getString("imported2", null)
        set(v) { sp.edit().putString("imported2", v).apply() }

    fun migrateLegacyDir() {
        if (sp.contains("base")) return
        val legacy = File(Environment.getExternalStorageDirectory(), "Android/Documents/Users/perfis")
        if (legacy.isDirectory) baseDir = legacy.path
    }
    var lastEngineUpdate: Long             // última verificação de versão nova do yt-dlp
        get() = sp.getLong("ytdlp_update", 0L)
        set(v) { sp.edit().putLong("ytdlp_update", v).apply() }
    var engineVersion: String?             // versão do yt-dlp instalada pelo atualizador do app
        get() = sp.getString("ytdlp_tag", null)
        set(v) { sp.edit().putString("ytdlp_tag", v).apply() }
    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi", false)
        set(v) { sp.edit().putBoolean("wifi", v).apply() }
    var parallel: Int                      // downloads simultâneos
        get() = sp.getInt("parallel", MAX_PARALLEL)          // padrão: o máximo
        set(v) { sp.edit().putInt("parallel", v).apply() }
    var themeMode: Int                     // 0 sistema, 1 claro, 2 escuro
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }
    var dynamic: Boolean
        get() = sp.getBoolean("dynamic", true)
        set(v) { sp.edit().putBoolean("dynamic", v).apply() }

    // cookies: ver CookieStore (Cookies.kt)
}

fun hasStorageAccess(ctx: Context, path: String?): Boolean {
    if (path == null) return false
    val own = ctx.getExternalFilesDir(null)?.parentFile?.path
    if (own != null && path.startsWith(own)) return true
    return if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

fun allFilesAccessIntent(ctx: Context) =
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))

/** Apaga a mídia, a miniatura e o .info.json. O id continua no archive.txt (não volta em atualizações). */
fun Post.deleteFiles() {
    val id = key.substringAfter(':')
    val f = File(mediaPath)
    f.delete()
    slideList().forEach { File(it).delete() }
    thumbPath?.let { File(it).delete() }
    File(File(f.parentFile, ".dados"), "$id.info.json").delete()
    File(File(f.parentFile, ".dados"), "$id.slides").delete()
    File(File(f.parentFile, ".dados"), "$id.comments.json").delete()
    File(f.parentFile, "$id.info.json").delete()   // layout antigo
}

/** Apaga todas as mídias do perfil mantendo o perfil e a foto. */
suspend fun clearMedia(dao: AppDao, p: Profile, allowRedownload: Boolean) = withContext(Dispatchers.IO) {
    val dir = File(p.dir)
    File(dir, "media").deleteRecursively()    // layout antigo
    dir.listFiles().orEmpty().filter { it.isFile && it.name != "pfp.jpg" }.forEach { it.delete() }
    File(dir, ".dados").listFiles().orEmpty().forEach { if (it.name != "archive.txt" || allowRedownload) it.delete() }
    dao.deletePosts(p.id); dao.recount(p.id)
    if (allowRedownload) dao.setSynced(p.id, false)
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "${b / 1024} KB"
}

/** seed = link de um vídeo do perfil (TikTok): usado para achar o secUid quando a página do perfil é bloqueada. */
data class Target(val platform: String, val label: String, val username: String, val url: String, val seed: String? = null)

fun parseProfileUrl(raw: String): Target? {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
    val seg = uri.pathSegments
    val first = seg.firstOrNull() ?: return null
    return when {
        host == "youtube.com" -> {
            val (name, base) = when {
                first.startsWith("@") -> first.drop(1) to "https://www.youtube.com/$first"
                first in setOf("channel", "c", "user") && seg.size > 1 ->
                    seg[1] to "https://www.youtube.com/$first/${seg[1]}"
                else -> return null
            }
            Target("youtube", "YouTube", name, "$base/videos")
        }
        host == "tiktok.com" && first.startsWith("@") -> {
            val isPost = seg.size >= 3 && seg[1] in setOf("video", "photo") && seg[2].all { it.isDigit() }
            Target("tiktok", "TikTok", first.drop(1), "https://www.tiktok.com/$first",
                seed = if (isPost) "https://www.tiktok.com/$first/video/${seg[2]}" else null)
        }
        host == "instagram.com" && first !in setOf("p", "reel", "reels", "stories", "explore") ->
            Target("instagram", "Instagram", first, "https://www.instagram.com/$first/")
        // Reddit: usuário (/user/nome, /u/nome) ou subreddit (/r/nome). Vira "u_nome" / "r_nome".
        host == "reddit.com" || host.endsWith(".reddit.com") -> {
            val name = seg.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            when (first.lowercase()) {
                "user", "u" -> Target("reddit", "Reddit", "u_$name", "https://www.reddit.com/user/$name/")
                "r" -> Target("reddit", "Reddit", "r_$name", "https://www.reddit.com/r/$name/")
                else -> null
            }
        }
        host == "twitter.com" || host == "x.com" ->
            if (first in setOf("i", "home", "explore", "search")) null
            else Target("x", "X", first, "https://x.com/$first")
        else -> null
    }
}


// ───────────── Pasta: seletor do sistema, preparo e verificação ─────────────

/** Converte o resultado do seletor de pastas (content://…/tree/primary:Download/Perfis) em caminho real. */
fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    val vol = parts[0]; val rel = parts.getOrElse(1) { "" }
    val root = if (vol.equals("primary", true)) Environment.getExternalStorageDirectory().path else "/storage/$vol"
    return if (rel.isEmpty()) root else "$root/$rel"
}

/** Cria a pasta, testa gravação e pede ao sistema para indexá-la (aparece nos gerenciadores de arquivos). */
fun prepareFolder(ctx: Context, path: String): String {
    if (!hasStorageAccess(ctx, path)) return "Conceda o acesso a todos os arquivos para gravar nessa pasta"
    val d = File(path)
    if (!(d.mkdirs() || d.isDirectory)) return "Não foi possível criar a pasta"
    val ok = runCatching { val t = File(d, ".teste"); t.writeText("ok"); t.delete() }.getOrDefault(false)
    MediaScannerConnection.scanFile(ctx, arrayOf(d.path), null, null)
    return if (ok) "Pasta pronta: ${d.path}" else "Sem permissão de gravação nessa pasta"
}

/**
 * Passa a usar [path] como pasta de downloads: cria/testa a pasta e procura perfis que já estejam nela.
 * Sem o acesso a todos os arquivos não dá para ler a pasta; a busca roda quando o acesso for concedido.
 */
suspend fun useFolder(ctx: Context, path: String): String = withContext(Dispatchers.IO) {
    val prefs = Prefs(ctx)
    prefs.baseDir = path
    if (!hasStorageAccess(ctx, path)) return@withContext "Pasta escolhida. Conceda o acesso a todos os arquivos para o app usar essa pasta"
    val msg = prepareFolder(ctx, path)
    val n = importExisting(Db.get(ctx).dao(), path)
    prefs.importedDir = path
    when (n) { 0 -> msg; 1 -> "1 perfil já baixado encontrado nessa pasta"; else -> "$n perfis já baixados encontrados nessa pasta" }
}

// ───────────── Metadados da publicação (tela "Sobre") ─────────────

fun loadMeta(profile: Profile, post: Post): List<Pair<String, String>> {
    val id = post.key.substringAfter(':')
    val j = runCatching { JSONObject(File(File(File(post.mediaPath).parentFile, ".dados"), "$id.info.json").readText()) }.getOrNull()
    val out = mutableListOf<Pair<String, String>>()
    fun add(k: String, v: String?) { if (!v.isNullOrBlank()) out += k to v }
    fun num(k: String): Long? = j?.let { if (it.has(k) && !it.isNull(k)) it.optLong(k) else null }
    add("Perfil", "${profile.handle()} · ${platformLabel(profile.platform)}")
    if (profile.platform == "reddit") {
        j?.optString("uploader")?.takeIf { it.isNotBlank() }?.let { add("Autor", "u/$it") }
        j?.optString("channel")?.takeIf { it.startsWith("r/") }?.let { add("Subreddit", it) }
        Reddit.readComments(File(post.mediaPath).parentFile!!, id)?.let { (n, list) ->
            add("Comentários salvos", "%,d de %,d".format(list.count { it.more == 0 }, n))
        }
    }
    add("Descrição", post.description.ifBlank { "[sem descrição]" })
    add("Publicado em", java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(post.timestamp)))
    post.likes?.let { add("Curtidas", "%,d".format(it)) }
    post.views?.let { add("Visualizações", "%,d".format(it)) }
    num("comment_count")?.let { add("Comentários", "%,d".format(it)) }
    num("repost_count")?.let { add("Compartilhamentos", "%,d".format(it)) }
    num("duration")?.takeIf { it > 0 }?.let { add("Duração", "%d:%02d".format(it / 60, it % 60)) }
    val w = num("width"); val h = num("height")
    if (w != null && h != null && w > 0) add("Resolução", "${w}×${h}")
    j?.optString("ext")?.takeIf { it.isNotBlank() }?.let { add("Formato", it) }
    j?.optJSONArray("tags")?.let { a -> add("Hashtags", (0 until a.length()).joinToString(", ") { a.optString(it) }) }
    val slides = post.slideList()
    add("Tipo", when { post.isVideo -> "Vídeo"; slides.isNotEmpty() -> "Fotos (${slides.size}) com música"; else -> "Foto" })
    if (profile.platform == "tiktok") j?.let { readMusic(File(post.mediaPath).parentFile!!, it) }?.let { m ->
        add("Música", if (m.contains) "Contém: " + listOfNotNull(m.title, m.author).joinToString(" · ")
            else if (m.original) (m.title ?: "Som original") + (m.author?.let { " · $it" } ?: "")
            else listOfNotNull(m.title, m.author).joinToString(" · "))
    }
    add("Tamanho do arquivo", formatSize((slides + post.mediaPath).sumOf { File(it).length() }))
    add("Link original", j?.optString("webpage_url"))
    add("Arquivo", post.mediaPath)
    add("ID", id)
    // quando esses dados foram buscados no site ("epoch" do yt-dlp), ou a data do arquivo de dados
    val dataFile = File(File(File(post.mediaPath).parentFile, ".dados"), "$id.info.json")
    val fetched = num("epoch")?.times(1000) ?: dataFile.takeIf { it.isFile }?.lastModified()
    fetched?.let { add("Informações atualizadas em", java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))) }
    return out
}
