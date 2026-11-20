@file:OptIn(UnstableApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.profilearchive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Ícones da barra de status brancos enquanto a tela tem fundo preto. */
@Composable
fun WhiteStatusBarIcons() {
    val view = LocalView.current
    val act = LocalContext.current.findActivity()
    DisposableEffect(Unit) {
        val c = act?.let { WindowCompat.getInsetsController(it.window, view) }
        val was = c?.isAppearanceLightStatusBars ?: false
        c?.isAppearanceLightStatusBars = false
        onDispose { c?.isAppearanceLightStatusBars = was }
    }
}

/**
 * Player configurado para arquivos locais:
 * - decoder fallback: se o decodificador de hardware recusar o arquivo, tenta o próximo em vez de dar erro;
 * - buffers curtos: arquivo local não precisa de 50 s de buffer; começa a tocar em ~250 ms e
 *   os 3 players do feed ocupam bem menos RAM.
 */
fun buildPlayer(ctx: Context, loop: Boolean, gain: GainProcessor? = null, audioFocus: Boolean = true): ExoPlayer {
    val renderers = object : DefaultRenderersFactory(ctx) {
        // ganho de volume (normalização) antes da saída de áudio
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
            if (gain == null) super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            else DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(gain))
                .build()
    }.setEnableDecoderFallback(true)
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(2_000, 15_000, 250, 500)
        .build()
    return ExoPlayer.Builder(ctx, renderers).setLoadControl(load).build().apply {
        repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        // audioFocus = false: não tira o foco de outro player do app (ex.: vídeo de carrossel x música do post)
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), audioFocus)
    }
}

@Composable
fun rememberPlayer(loop: Boolean, audioFocus: Boolean = true): ExoPlayer {
    val ctx = LocalContext.current
    val player = remember { buildPlayer(ctx, loop, audioFocus = audioFocus) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o); player.release() }
    }
    return player
}

private fun ExoPlayer.load(path: String) {
    setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path)))); prepare(); play()
}

internal fun inflatePlayer(c: Context, withControls: Boolean): PlayerView =
    LayoutInflater.from(c).inflate(if (withControls) R.layout.player_texture_controls else R.layout.player_texture, null) as PlayerView

// ───────────────────────── Menu ⋮ (Sobre / Excluir) ─────────────────────────

@Composable
fun PostMenu(profile: Profile, post: Post, tint: Color, modifier: Modifier = Modifier, onDelete: (Post) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Mais opções", tint = tint) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Sobre") }, { menu = false; about = true }, leadingIcon = { Icon(Icons.Default.Info, null) })
            DropdownMenuItem({ Text("Excluir") }, { menu = false; confirm = true }, leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
    if (about) AboutSheet(profile, post) { about = false }
    if (confirm) ConfirmDeleteMedia({ onDelete(post); confirm = false }, { confirm = false })
}

@Composable
fun AboutSheet(profile: Profile, post: Post, onDismiss: () -> Unit) {
    val meta by produceState<List<Pair<String, String>>>(emptyList(), post.key) {
        value = withContext(Dispatchers.IO) { loadMeta(profile, post) }
    }
    ModalBottomSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Sobre", style = MaterialTheme.typography.titleLarge)
            meta.forEach { (k, v) ->
                Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp))
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ───────────────────────── Tela de publicação: escolhe o player por plataforma ─────────────────────────

@Composable
fun PostScreen(key: String, nav: NavController) {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    val pid = remember(key) { key.substringBefore(':').toLong() }
    val post by remember(key) { dao.post(key) }.collectAsState(null)
    // mesmos dados em cache da tela do perfil: o feed abre já com a lista pronta
    val posts by remember(pid) { ProfileData.posts(ctx, pid) }.collectAsState()
    val profile by remember(pid) { ProfileData.profile(ctx, pid) }.collectAsState()
    val prof = profile
    val list = posts
    var seen by remember { mutableStateOf(false) }

    val delete: (Post) -> Unit = { p ->
        scope.launch(Dispatchers.IO) { p.deleteFiles(); dao.deletePost(p.key); dao.recount(p.profileId) }
    }

    if (prof == null || list == null) { Box(Modifier.fillMaxSize().background(Color.Black)); return }

    when (prof.platform) {
        "tiktok", "instagram", "reddit" -> {
            LaunchedEffect(list.isEmpty()) { if (list.isEmpty()) nav.popBackStack() }
            if (list.isNotEmpty()) FeedPager(prof, list, key, nav, delete)
        }
        else -> {
            LaunchedEffect(post) { if (post != null) seen = true else if (seen) nav.popBackStack() }
            val p = post ?: return
            if (prof.platform == "youtube") YouTubeWatch(prof, p, nav, delete) else TweetDetail(prof, p, nav, delete)
        }
    }
}

// ───────────────────────── YouTube ─────────────────────────

@Composable
fun YouTubeWatch(profile: Profile, post: Post, nav: NavController, onDelete: (Post) -> Unit) {
    WhiteStatusBarIcons()
    val act = LocalContext.current.findActivity()
    val view = LocalView.current
    val player = rememberPlayer(loop = false)
    var fs by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    // proporção real (Shorts são verticais); sem dimensões salvas, 16:9
    val ratio = remember(post.key) {
        val w = post.width ?: 0; val h = post.height ?: 0
        if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(9f / 16f, 21f / 9f) else 16f / 9f
    }
    LaunchedEffect(post.mediaPath) { player.load(post.mediaPath) }

    // Sair da tela cheia pelo voltar "aperta" o botão do player, para o ícone dele acompanhar o estado
    BackHandler(fs) {
        playerView?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_fullscreen)?.performClick() ?: run { fs = false }
    }
    // Tela cheia: paisagem para vídeo horizontal, retrato para vertical, sem barras do sistema
    DisposableEffect(fs) {
        val c = act?.let { WindowCompat.getInsetsController(it.window, view) }
        if (fs) {
            act?.requestedOrientation = if (ratio >= 1f) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            c?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Uma estrutura só para normal e tela cheia: o mesmo PlayerView continua na tela ao alternar
    // (antes eram dois players diferentes: a imagem sumia e o vídeo piscava a cada troca).
    BoxWithConstraints(Modifier.fillMaxSize().background(if (fs) Color.Black else MaterialTheme.colorScheme.background)) {
        val playerH = if (fs) maxHeight else minOf(maxWidth / ratio, maxHeight * 0.6f)
        Column(Modifier.fillMaxSize().then(if (fs) Modifier else Modifier.swipeToBack { nav.popBackStack() })) {
            Box(Modifier.fillMaxWidth().background(Color.Black).then(if (fs) Modifier else Modifier.statusBarsPadding())) {
                AndroidView(
                    { c -> inflatePlayer(c, true).apply { setFullscreenButtonClickListener { fs = it }; playerView = this } },
                    Modifier.fillMaxWidth().height(playerH),
                    update = { it.player = player },
                    onRelease = { it.player = null; playerView = null }
                )
                if (!fs) {
                    IconButton({ nav.popBackStack() }, Modifier.align(Alignment.TopStart)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White)
                    }
                    PostMenu(profile, post, Color.White, Modifier.align(Alignment.TopEnd), onDelete)
                }
            }
            if (!fs) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp)) {
                Text(post.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(post.views?.let { "${it.compact()} visualizações" }, post.timestamp.date()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(profile.avatarPath, 40); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayName, fontWeight = FontWeight.Medium)
                        profile.followers?.let { Text("${it.compact()} inscritos", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (post.description.isNotBlank()) Card(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Text(post.description, Modifier.padding(12.dp), maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ───────────────────────── X ─────────────────────────

@Composable
fun TweetDetail(profile: Profile, post: Post, nav: NavController, onDelete: (Post) -> Unit) {
    val player = rememberPlayer(loop = true)
    LaunchedEffect(post.mediaPath) { if (post.isVideo) player.load(post.mediaPath) }
    Scaffold(
        modifier = Modifier.swipeToBack { nav.popBackStack() },
        topBar = {
            TopAppBar(title = { Text("Post") },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                actions = { PostMenu(profile, post, MaterialTheme.colorScheme.onSurface, onDelete = onDelete) })
        }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(profile.avatarPath, 48); Spacer(Modifier.width(12.dp))
                Column {
                    Text(profile.displayName, fontWeight = FontWeight.Bold)
                    Text("@${profile.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (post.description.isNotBlank()) Text(post.description, fontSize = 20.sp, modifier = Modifier.padding(vertical = 12.dp))
            if (post.isVideo) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                AndroidView({ c -> inflatePlayer(c, false) }, Modifier.fillMaxSize(), update = { it.player = player })
                VideoControls(player, fullscreen = false, onFullscreen = null, onMinimize = null, drag = null)
            } else AsyncImage(File(post.mediaPath), null, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.FillWidth)
            Text(post.timestamp.date(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
        }
    }
}
