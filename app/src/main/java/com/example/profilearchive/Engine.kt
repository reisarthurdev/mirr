package com.example.profilearchive

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Motor de download (yt-dlp embutido).
 *
 * - init: prepara python/ffmpeg e instala o plugin do app (fotos do TikTok + secUid).
 * - acquire/release: conta downloads ativos. A atualização automática do yt-dlp só acontece
 *   com zero downloads rodando, porque trocar o binário no meio de um processo quebra o processo.
 */
object Engine {
    private const val UPDATE_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val NIGHTLY_API = "https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest"
    private const val PLUGIN_DIR = "ytdlp"

    private var ready = false
    private var active = 0

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        val app = ctx.applicationContext
        var last: Exception? = null
        repeat(3) {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                runCatching { installPlugin(app) }   // sem plugin o app funciona, só perde as fotos dos slides
                runCatching { prune(app) }           // tira do python/ffmpeg extraídos o que nunca é usado
                ready = true
                return
            } catch (e: Exception) { last = e; Thread.sleep(800) }
        }
        throw last ?: IllegalStateException("Falha ao iniciar o yt-dlp")
    }

    /** Marca um download ativo; antes do primeiro, atualiza o yt-dlp se a última checagem for antiga. */
    @Synchronized
    fun acquire(ctx: Context) {
        init(ctx)
        val prefs = Prefs(ctx)
        if (active == 0 && System.currentTimeMillis() - prefs.lastEngineUpdate > UPDATE_EVERY_MS) {
            prefs.lastEngineUpdate = System.currentTimeMillis()   // marca antes: sem rede não tenta de novo a cada download
            runCatching { update(ctx, prefs) }
        }
        active++
    }

    @Synchronized
    fun release() { active = (active - 1).coerceAtLeast(0) }

    /** Botão "Atualizar yt-dlp" das Configurações. */
    @Synchronized
    fun updateNow(ctx: Context): String {
        init(ctx)
        if (active > 0) return "Espere os downloads terminarem para atualizar"
        val prefs = Prefs(ctx)
        return runCatching { update(ctx, prefs) }
            .onSuccess { prefs.lastEngineUpdate = System.currentTimeMillis() }
            .getOrElse { "Erro ao atualizar: ${it.message}" }
    }

    /**
     * Baixa o yt-dlp nightly (corrige quebras do TikTok/Instagram dias antes do stable).
     * Feito aqui em vez do updater da biblioteca porque aquele não tem timeout de rede
     * e poderia travar todos os downloads esperando o GitHub.
     */
    private fun update(ctx: Context, prefs: Prefs): String {
        val release = JSONObject(httpGet(NIGHTLY_API).bufferedReader().use { it.readText() })
        val tag = release.getString("tag_name")
        if (tag == prefs.engineVersion) return "yt-dlp já está na versão mais nova ($tag)"
        val assets = release.getJSONArray("assets")
        val url = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == YoutubeDL.ytdlpBin }
            ?.optString("browser_download_url") ?: error("arquivo do yt-dlp não encontrado no release")

        val dir = File(ctx.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}").apply { mkdirs() }
        val tmp = File(dir, "${YoutubeDL.ytdlpBin}.new")
        httpGet(url).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (tmp.length() < 1_000_000) { tmp.delete(); error("download incompleto") }
        // rename é atômico: nunca existe um yt-dlp pela metade
        if (!tmp.renameTo(File(dir, YoutubeDL.ytdlpBin))) { tmp.delete(); error("não foi possível instalar") }
        prefs.engineVersion = tag
        return "yt-dlp atualizado para $tag"
    }

    private fun httpGet(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "ProfileArchive")
        if (c.responseCode !in 200..299) { c.disconnect(); error("HTTP ${c.responseCode}") }
        return c.inputStream
    }

    /**
     * A biblioteca extrai python e ffmpeg completos para o armazenamento interno (é a maior parte do
     * tamanho do app instalado). Parte disso nunca é usada pelo yt-dlp/ffmpeg em tempo de execução:
     * - testes e ferramentas gráficas da biblioteca padrão do python (test, idlelib, tkinter…);
     * - cabeçalhos C (include), bibliotecas estáticas (.a), manuais e documentação: só servem para compilar.
     * A biblioteca só extrai de novo quando a versão dela muda, então o que sai aqui não volta;
     * se voltar (versão nova), é apagado de novo. Também limpa o cache de páginas do yt-dlp com mais de 14 dias.
     */
    private fun prune(ctx: Context) {
        val packages = File(ctx.noBackupFilesDir, "${YoutubeDL.baseName}/packages")
        val unusedStdlib = setOf("test", "idlelib", "tkinter", "turtledemo", "ensurepip", "lib2to3", "pydoc_data", "venv", "__phello__")
        packages.listFiles().orEmpty().forEach { pkg ->
            val usr = File(pkg, "usr")
            listOf("include", "share/man", "share/doc", "share/info", "lib/pkgconfig").forEach { File(usr, it).deleteRecursively() }
            File(usr, "lib").listFiles { f -> f.isDirectory && f.name.startsWith("python3") }.orEmpty().forEach { py ->
                unusedStdlib.forEach { File(py, it).deleteRecursively() }
            }
            usr.walkTopDown().onEnter { it.name != ".yt-dlp" }.filter { it.isFile && it.name.endsWith(".a") }.forEach { it.delete() }
        }
        val old = System.currentTimeMillis() - 14 * 24 * 3600_000L
        File(packages, "python/usr/.cache").walkBottomUp().forEach { f ->
            if (f.isFile && f.lastModified() < old) f.delete() else if (f.isDirectory) f.delete()   // delete() em pasta só apaga se vazia
        }
    }

    /**
     * O yt-dlp roda com HOME = <python>/usr e carrega sozinho os plugins de
     * ~/.yt-dlp/plugins/<pacote>/yt_dlp_plugins/extractor. Copia todos os .py de assets/ytdlp
     * (TikTok, Instagram…), reescrevendo só os que mudaram.
     */
    private fun installPlugin(ctx: Context) {
        val home = File(ctx.noBackupFilesDir, "${YoutubeDL.baseName}/packages/python/usr")
        val dir = File(home, ".yt-dlp/plugins/profilearchive/yt_dlp_plugins/extractor").apply { mkdirs() }
        ctx.assets.list(PLUGIN_DIR).orEmpty().filter { it.endsWith(".py") }.forEach { name ->
            val bytes = ctx.assets.open("$PLUGIN_DIR/$name").use { it.readBytes() }
            val target = File(dir, name)
            if (!(target.isFile && target.readBytes().contentEquals(bytes))) target.writeBytes(bytes)
        }
    }
}

/**
 * Ordenação de formatos (-S do yt-dlp) montada a partir dos decodificadores de HARDWARE do aparelho:
 * 1. sem HDR (o player usa TextureView, que não faz tone mapping: HDR sairia lavado);
 * 2. a maior resolução que o aparelho decodifica em hardware;
 * 3. maior fps;
 * 4. o codec mais eficiente que o aparelho acelera e que aguenta essa resolução (AV1 > VP9 > H.265 > H.264);
 * 5. maior bitrate.
 * Resultado: qualidade máxima sem cair em decodificação por software (que engasga).
 */
object DeviceVideo {
    private val LADDER = listOf(
        "av01" to "video/av01",
        "vp9" to "video/x-vnd.on2.vp9",
        "h265" to "video/hevc",
        "h264" to "video/avc",
    )
    // até 4K: acima disso (8K) o arquivo é enorme e a reprodução numa TextureView engasga em qualquer celular
    private val RES_STEPS = listOf(2160, 1440, 1080, 720, 480)

    val sort: String by lazy {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && isHardware(it) }
        }.getOrDefault(emptyList())

        fun maxRes(mime: String): Int = decoders
            .filter { d -> d.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .maxOfOrNull { d ->
                val vc = runCatching { d.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                // maior resolução que o decodificador aguenta a 60 fps (vídeos de 60 fps não podem engasgar)
                if (vc == null) 0 else RES_STEPS.firstOrNull { r ->
                    runCatching { vc.areSizeAndRateSupported(r * 16 / 9, r, 60.0) }.getOrDefault(false)
                } ?: RES_STEPS.firstOrNull { r -> vc.isSizeSupported(r * 16 / 9, r) } ?: 0
            } ?: 0

        val caps = LADDER.map { (name, mime) -> name to maxRes(mime) }
        val top = caps.maxOf { it.second }.takeIf { it > 0 } ?: 1080
        val codec = caps.firstOrNull { it.second >= top }?.first ?: "h264"
        "hdr:sdr,res:$top,fps,vcodec:$codec,br"
    }

    private fun isHardware(i: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) i.isHardwareAccelerated
        else i.name.lowercase().let { !it.startsWith("omx.google.") && !it.startsWith("c2.android.") && ".sw." !in it }
}
