@file:OptIn(UnstableApi::class, ExperimentalFoundationApi::class)

package com.example.profilearchive

import android.net.Uri
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.activity.compose.BackHandler
import org.json.JSONObject
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.media3.exoplayer.SeekParameters
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.NavController
import coil.compose.AsyncImage
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.math.abs

// ───────────────────────── Escala estilo TikTok ─────────────────────────

/**
 * Como o feed do TikTok decide entre ampliar (preencher a tela) e encaixar (barras pretas).
 * Não depende da resolução em pixels, e sim da PROPORÇÃO:
 * - mídia vertical "cheia" (largura/altura ≤ 10:16, ou seja 9:16, 9:18, 9:19.5…) é ampliada com
 *   corte central, desde que o corte não passe de 30% de um lado. Em telas 20:9 isso apara
 *   umas faixas laterais de um vídeo 9:16, exatamente como o app faz;
 * - quadrado, 4:5, 3:4, paisagem, ou tela larga demais (tablet/dobrável aberto): mostra inteiro.
 */
object FeedScale {
    private const val MAX_PORTRAIT_RATIO = 10f / 16f
    private const val MAX_CROP = 0.30f

    fun shouldFill(mediaW: Float, mediaH: Float, viewW: Float, viewH: Float): Boolean {
        if (mediaW <= 0f || mediaH <= 0f || viewW <= 0f || viewH <= 0f) return false
        val media = mediaW / mediaH
        if (media > MAX_PORTRAIT_RATIO) return false
        val view = viewW / viewH
        val cropped = 1f - minOf(media, view) / maxOf(media, view)   // fração perdida no lado cortado
        return cropped <= MAX_CROP
    }
}

/** ContentScale com a regra acima: imagens e miniaturas decidem sozinhas, sem estado e sem "pulo". */
object TikTokScale : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor =
        if (FeedScale.shouldFill(srcSize.width, srcSize.height, dstSize.width, dstSize.height))
            ContentScale.Crop.computeScaleFactor(srcSize, dstSize)
        else ContentScale.Fit.computeScaleFactor(srcSize, dstSize)
}

/** Tamanho de exibição do vídeo, já considerando rotação e pixels não quadrados. */
private fun VideoSize.displaySize(): IntSize? {
    if (width <= 0 || height <= 0) return null
    val w = (width * pixelWidthHeightRatio).toInt()
    return if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) IntSize(height, w) else IntSize(w, height)
}

// ───────────────────────── Página do feed ─────────────────────────


@Composable
private fun FeedPage(
    profile: Profile, post: Post, player: ExoPlayer?, videoSize: IntSize?,
    active: Boolean, paused: Boolean, hideCaption: Boolean,
    onTap: () -> Unit, onHold: (Boolean) -> Unit,
    resetZoom: Int = 0, onZoom: (Boolean) -> Unit = {},
    onMusic: () -> Unit = {}, onSlideSound: (Boolean) -> Unit = {}
) {
    val isTikTok = profile.platform == "tiktok"
    val isReddit = profile.platform == "reddit"
    val tap by rememberUpdatedState(onTap)     // os lambdas mudam quando a página assentada muda
    val hold by rememberUpdatedState(onHold)
    var expanded by remember(post.key) { mutableStateOf(false) }

    // zoom com pinça (igual ao TikTok): ampliado, a interface some e o feed não rola; pinçar de volta restaura
    val zoom = rememberFeedZoom(post.key)
    val zoomed = zoom.zoomed
    LaunchedEffect(zoomed) { onZoom(zoomed); if (zoomed) hold(false) }
    LaunchedEffect(active, resetZoom) { if (!active || resetZoom > 0) zoom.reset() }
    val uiHidden = hideCaption || zoomed

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        Box(Modifier.fillMaxSize().then(zoom.modifier)) {
        when {
            // TikTok avança as fotos sozinho; Instagram não
            post.isSlideshow -> Slides(post, active, paused, autoAdvance = isTikTok, zoomed = zoomed, onSlideSound = onSlideSound) { tap() }
            post.isVideo -> {
                // Sem flicker: a miniatura fica POR BAIXO do vídeo até o player desenhar o 1º frame
                // nesta superfície (cada troca de superfície zera o aviso de "1º frame").
                var frameOnScreen by remember(post.key) { mutableStateOf(false) }
                DisposableEffect(player) {
                    frameOnScreen = false
                    val l = object : Player.Listener {
                        override fun onRenderedFirstFrame() { frameOnScreen = true }
                    }
                    player?.addListener(l)
                    onDispose { player?.removeListener(l) }
                }
                // dimensões salvas no download; se faltarem (posts antigos), as que o player informar
                val w = post.width?.takeIf { it > 0 } ?: videoSize?.width ?: 0
                val h = post.height?.takeIf { it > 0 } ?: videoSize?.height ?: 0
                val fill = FeedScale.shouldFill(w.toFloat(), h.toFloat(), viewW, viewH)

                if (!frameOnScreen) AsyncImage(thumbOf(post), null, Modifier.fillMaxSize(), contentScale = TikTokScale)
                // A view existe desde que a página entra na tela; o player é só conectado/desconectado.
                AndroidView(
                    { c -> inflatePlayer(c, false) }, Modifier.fillMaxSize(),
                    update = { v ->
                        v.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        // proporção definida já na criação: sem ela a área do vídeo é redimensionada quando o
                        // player descobre o tamanho, e a TextureView realoca o buffer (um piscar)
                        if (w > 0 && h > 0) v.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                            ?.setAspectRatio(w.toFloat() / h)
                        if (v.player !== player) {
                            v.player = player
                            // pausado, o ExoPlayer não redesenha numa superfície nova: um seek no mesmo
                            // ponto força o 1º frame, e o próximo vídeo já aparece durante o swipe
                            player?.takeIf { !it.isPlaying && it.playbackState != Player.STATE_IDLE }
                                ?.let { it.seekTo(it.currentPosition) }
                        }
                    },
                    onRelease = { it.player = null }
                )
                // toque = pausa/continua; segurar = 2x enquanto o dedo estiver na tela (igual ao TikTok)
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { tryAwaitRelease(); hold(false) },
                        onLongPress = { hold(true) },
                        onTap = { tap() }
                    )
                })
                PausedIcon(active && paused, Modifier.align(Alignment.Center), hidden = zoomed)
            }
            else -> AsyncImage(File(post.mediaPath), null, Modifier.fillMaxSize(), contentScale = TikTokScale)
        }
        }

        // disco da música (TikTok): toque abre o nome e o autor
        if (isTikTok && !uiHidden) MusicDisc(profile, post,
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 30.dp), onMusic)
        // Reddit: botão dos comentários salvos
        if (isReddit && !uiHidden) CommentsButton(post,
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 8.dp, bottom = 30.dp))

        // legenda: usuário + descrição (sem foto de perfil e sem degradê); some enquanto arrasta a linha do tempo
        if (!uiHidden) Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 14.dp, end = 72.dp, bottom = 46.dp)) {
            Text(when { isTikTok -> "@${profile.username}"; isReddit -> profile.handle(); else -> profile.username },
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (isTikTok) 17.sp else 15.sp)
            // Instagram: música escolhida para o post, embaixo do usuário (como no app)
            if (profile.platform == "instagram") MusicLine(post)
            // Reddit: o título é o texto principal do post; o corpo (se houver) vem embaixo
            if (isReddit && post.title.isNotBlank()) Text(post.title, color = Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = if (expanded) 6 else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).clickable { expanded = !expanded })
            if (post.description.isNotBlank()) Text(
                post.description, color = Color.White, fontSize = 14.sp,
                maxLines = if (expanded) 12 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).clickable { expanded = !expanded }
            )
        }
    }
}

// ───────────────────────── Música só no trecho escolhido ─────────────────────────

/** Trecho da música (início, fim em ms) por post; lido do .info.json uma vez e guardado. */
private val clipCache = java.util.concurrent.ConcurrentHashMap<String, LongArray>()

/**
 * Item do player para o post. Post de fotos do Instagram toca só o trecho da música que o autor
 * escolheu (e repete esse trecho), igual ao app; o resto toca o arquivo inteiro.
 */
private fun mediaItemOf(post: Post): MediaItem {
    val uri = Uri.fromFile(File(post.mediaPath))
    if (!post.isSlideshow) return MediaItem.fromUri(uri)
    val clip = clipCache.getOrPut(post.key) {
        val m = runCatching {
            JSONObject(File(metaDir(File(post.mediaPath).parentFile!!), "${post.key.substringAfter(':')}.info.json").readText())
                .optJSONObject("pa_music")
        }.getOrNull()
        val start = m?.optLong("start_ms", -1L) ?: -1L
        val length = m?.optLong("length_ms", -1L) ?: -1L
        if (start < 0) longArrayOf() else longArrayOf(start, if (length > 0) start + length else C.TIME_END_OF_SOURCE)
    }
    if (clip.isEmpty()) return MediaItem.fromUri(uri)
    return MediaItem.Builder().setUri(uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clip[0]).setEndPositionMs(clip[1]).build())
        .build()
}

// ───────────────────────── Zoom com pinça ─────────────────────────

/**
 * Zoom do feed igual ao TikTok: dois dedos ampliam (até 5x) em volta do ponto entre os dedos e
 * movem a imagem; ampliado, um dedo arrasta. Ao soltar quase sem zoom, volta ao normal com animação.
 *
 * Os eventos são lidos na passagem "Initial" (antes do pager e do toque/segurar da página):
 * consumidos aqui, o pager não rola e o "segurar = 2x" não dispara durante a pinça.
 * Com um dedo e sem zoom nada é consumido, então swipe, toque e segurar funcionam como antes.
 */
@Stable
private class FeedZoom(private val scope: kotlinx.coroutines.CoroutineScope) {
    var scale by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    val zoomed get() = scale > 1.01f
    private var anim: kotlinx.coroutines.Job? = null
    private var lastTapTime = 0L
    private var lastTapAt = Offset.Zero

    /** Volta ao tamanho normal animando escala e deslocamento juntos. */
    fun reset() {
        if (scale == 1f && pan == Offset.Zero) return
        val s0 = scale; val p0 = pan
        anim?.cancel()
        anim = scope.launch {
            animate(0f, 1f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { f, _ ->
                scale = s0 + (1f - s0) * f; pan = p0 * (1f - f)
            }
        }
    }

    /** Limita o arrasto para a imagem ampliada não sair da tela. */
    private fun clamp(p: Offset, s: Float, w: Float, h: Float): Offset {
        val mx = ((s - 1f) * w / 2f).coerceAtLeast(0f); val my = ((s - 1f) * h / 2f).coerceAtLeast(0f)
        return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
    }

    val modifier: Modifier = Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                anim?.cancel()
                // já ampliado: o gesto inteiro é do zoom (o toque não pausa o vídeo; dois toques voltam ao normal)
                val zoomedAtStart = zoomed
                if (zoomedAtStart) down.consume()
                var pinching = false
                var moved = 0f
                do {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (ev.changes.count { it.pressed } >= 2) pinching = true
                    moved += ev.calculatePan().getDistance()
                    if (zoomedAtStart) ev.changes.forEach { it.consume() }
                    if (pinching || zoomed) {
                        val c = ev.calculateCentroid(useCurrent = true)
                        if (c != Offset.Unspecified) {
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val next = (scale * ev.calculateZoom()).coerceIn(0.8f, 5f)
                            // o ponto entre os dedos fica parado na tela enquanto a escala muda
                            val fromCenter = c - Offset(w / 2f, h / 2f)
                            pan = clamp((pan - fromCenter) * (next / scale) + fromCenter + ev.calculatePan(), next, w, h)
                            scale = next
                        }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
                // toque duplo com zoom: volta ao normal
                if (zoomedAtStart && !pinching && moved < viewConfiguration.touchSlop) {
                    val now = down.uptimeMillis
                    val near = (down.position - lastTapAt).getDistance() < 48.dp.toPx()
                    if (now - lastTapTime < viewConfiguration.doubleTapTimeoutMillis && near) { reset(); lastTapTime = 0L }
                    else { lastTapTime = now; lastTapAt = down.position }
                } else lastTapTime = 0L
                if (scale < 1.08f) reset()
            }
        }
}

@Composable
private fun rememberFeedZoom(key: String): FeedZoom {
    val scope = rememberCoroutineScope()
    return remember(key) { FeedZoom(scope) }
}

// ───────────────────────── Comentários (Reddit) ─────────────────────────

/** Balão com o nº de comentários; toque abre os comentários salvos do post. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsButton(post: Post, modifier: Modifier) {
    val dir = remember(post.key) { File(post.mediaPath).parentFile }
    val id = post.key.substringAfter(':')
    val count by produceState(-1L, post.key) {
        value = withContext(Dispatchers.IO) {
            runCatching { JSONObject(File(metaDir(dir!!), "$id.info.json").readText()).optLong("comment_count", -1) }.getOrDefault(-1L)
        }
    }
    var show by remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable { show = true }.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_comment), "Comentários", Modifier.size(30.dp), tint = Color.White)
        if (count >= 0) Text(count.compact(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
    if (show) ModalBottomSheet(onDismissRequest = { show = false }) {
        val data by produceState<Pair<Long, List<Reddit.Comment>>?>(null, post.key) {
            value = withContext(Dispatchers.IO) { dir?.let { Reddit.readComments(it, id) } ?: (0L to emptyList()) }
        }
        val d = data
        Text(if (d == null) "Comentários" else "${d.first.compact()} comentários",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        when {
            d == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            d.second.isEmpty() -> Text("Nenhum comentário salvo. Use \"Reverificar perfil\" para baixar os comentários.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp).navigationBarsPadding())
            else -> LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(d.second.size) { i -> CommentRow(d.second[i]) }
            }
        }
    }
}

@Composable
private fun CommentRow(c: Reddit.Comment) {
    val line = MaterialTheme.colorScheme.outlineVariant
    // recuo por nível com uma linha vertical por nível, como no Reddit
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 16.dp, end = 16.dp)) {
        repeat(c.depth.coerceAtMost(8)) {
            Box(Modifier.width(12.dp).fillMaxHeight()) { Box(Modifier.width(1.dp).fillMaxHeight().background(line)) }
        }
        if (c.more > 0) Text("+${c.more} respostas não salvas", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        else Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("u/${c.author}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (c.op) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (c.op) Text(" · OP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(" · ${c.score.compact()} pontos · ${c.time.date()}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(c.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** "♫ Artista · Música" do post (Instagram); some se o post não tem música. */
@Composable
private fun MusicLine(post: Post) {
    val music by produceState<Music?>(null, post.key) {
        value = withContext(Dispatchers.IO) {
            val dir = File(post.mediaPath).parentFile ?: return@withContext null
            runCatching { readMusic(dir, JSONObject(File(metaDir(dir), "${post.key.substringAfter(':')}.info.json").readText())) }.getOrNull()
        }
    }
    val m = music ?: return
    Text("♫ " + listOfNotNull(m.author, m.title).joinToString(" · "), color = Color.White, fontSize = 13.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
}

/** Dados da música do post (do .info.json, lido fora da thread principal). */
@Composable
private fun rememberMusic(post: Post): State<Music?> = produceState<Music?>(null, post.key) {
    value = withContext(Dispatchers.IO) {
        val dir = File(post.mediaPath).parentFile ?: return@withContext null
        runCatching { readMusic(dir, JSONObject(File(metaDir(dir), "${post.key.substringAfter(':')}.info.json").readText())) }.getOrNull()
    }
}

/**
 * Disco da música igual ao TikTok (parado): capa redonda, para qualquer música, original ou não.
 * Sem capa baixada usa a foto do perfil. Toque abre a folha com nome e autor ([onClick]).
 */
@Composable
private fun MusicDisc(profile: Profile, post: Post, modifier: Modifier, onClick: () -> Unit) {
    val music by rememberMusic(post)
    val image: Any? = music?.cover ?: profile.avatarPath?.let(::File)
    // capa ocupando o círculo inteiro, sem aro em volta (igual ao app do TikTok)
    AsyncImage(image, "Música", modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentScale = ContentScale.Crop)
}

/** Conteúdo da folha da música: capa, nome e autor. */
@Composable
private fun MusicInfo(profile: Profile, post: Post) {
    val music by rememberMusic(post)
    val m = music
    val image: Any? = m?.cover ?: profile.avatarPath?.let(::File)
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(image, null, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF222222)),
            contentScale = ContentScale.Crop)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(when {
                    m == null -> "Música desconhecida"
                    m.contains -> "Contém: ${m.title ?: "música"}"
                    m.original -> m.title ?: "Som original"
                    else -> m.title ?: "Sem nome"
                },
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when {
                m == null -> "Verifique o perfil de novo para buscar os dados da música"
                m.original -> m.author ?: "@${profile.username}"
                else -> m.author
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Folha que sobe de baixo, desenhada na própria tela do feed (não é uma janela nova como o
 * ModalBottomSheet): abrir não trava o vídeo e a sombra cobre a tela inteira de uma vez, inclusive
 * atrás da barra de status. Toque fora, voltar ou arrastar para baixo fecha.
 */
@Composable
private fun FeedSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val t by animateFloatAsState(if (visible) 1f else 0f,
        tween(if (visible) 260 else 200, easing = FastOutSlowInEasing), label = "folha")
    var drag by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) { if (visible) drag = 0f }
    if (!visible && t == 0f) return
    BackHandler(visible, onDismiss)
    val dismiss by rememberUpdatedState(onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = t }.background(Color.Black.copy(alpha = .4f))
            .pointerInput(visible) { if (visible) detectTapGestures { dismiss() } })
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { translationY = size.height * (1f - t) + drag }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (drag > 64.dp.toPx()) dismiss() else drag = 0f },
                        onDragCancel = { drag = 0f }
                    ) { ch, dy -> ch.consume(); drag = (drag + dy).coerceAtLeast(0f) }
                },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp).size(32.dp, 4.dp)
                    .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)))
                content()
            }
        }
    }
}

/** Vídeo dentro de um carrossel (Instagram): toca só quando é a página visível do carrossel. */
@Composable
private fun SlideVideo(path: String, playing: Boolean, modifier: Modifier) {
    // pausa ao sair do app e é liberado ao sair da tela; sem foco de áudio próprio, senão a música do post
    // perdia o foco e parava de vez quando um vídeo do carrossel começava
    val player = rememberPlayer(loop = true, audioFocus = false)
    LaunchedEffect(path) { player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path)))); player.prepare() }
    LaunchedEffect(playing) { if (playing) player.play() else player.pause() }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ c -> inflatePlayer(c, false).also { it.player = player } }, Modifier.fillMaxSize(), onRelease = { it.player = null })
        Box(modifier)
    }
}

@Composable
/**
 * Ícone de pausa como no TikTok: ao pausar o "play" surge grande e encolhe até o tamanho normal com fade;
 * ao continuar ele cresce um pouco e some.
 */
private fun PausedIcon(visible: Boolean, modifier: Modifier, hidden: Boolean = false) {
    // [hidden] (zoom com pinça): some encolhendo um pouco e volta do mesmo jeito ao tirar o zoom
    AnimatedVisibility(
        !hidden, modifier,
        enter = scaleIn(tween(200, easing = FastOutSlowInEasing), initialScale = .8f) + fadeIn(tween(200)),
        exit = scaleOut(tween(160), targetScale = .8f) + fadeOut(tween(160))
    ) {
        AnimatedVisibility(
            visible,
            enter = scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 1.6f) + fadeIn(tween(160)),
            exit = scaleOut(tween(180), targetScale = 1.25f) + fadeOut(tween(180))
        ) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(88.dp), tint = Color.White.copy(alpha = .8f))
        }
    }
}

// ───────────────────────── Post de fotos (modo foto do TikTok) ─────────────────────────

private const val SLIDE_MS = 3_000L

/**
 * Igual ao app: fotos lado a lado com swipe horizontal, avançam sozinhas a cada 3 s
 * (voltam para a primeira no fim), música em loop, toque pausa música e avanço,
 * contador "n/N" no topo e bolinhas embaixo.
 */
@Composable
private fun Slides(
    post: Post, active: Boolean, paused: Boolean, autoAdvance: Boolean, zoomed: Boolean,
    onSlideSound: (Boolean) -> Unit, onTap: () -> Unit
) {
    val images = remember(post.key) { post.slideList() }
    val pager = rememberPagerState { images.size }
    fun isVideo(i: Int) = images.getOrNull(i)?.substringAfterLast('.')?.lowercase() in VIDEO_EXTS

    // música do post toca em todas as fotos; num vídeo do carrossel com som próprio ela pausa
    // e volta na próxima foto/vídeo sem som. Os vídeos são medidos antes (fica guardado).
    LaunchedEffect(active) {
        if (active) withContext(Dispatchers.IO) { images.filter { it.substringAfterLast('.').lowercase() in VIDEO_EXTS }.forEach { Loudness.rms(it) } }
    }
    val here = pager.settledPage
    val soundHere by produceState(false, here, post.key) {
        value = isVideo(here) && withContext(Dispatchers.IO) { Loudness.hasSound(images[here]) }
    }
    LaunchedEffect(active, soundHere) { if (active) onSlideSound(soundHere) }

    // reinicia o contador a cada troca de foto; não avança enquanto o dedo arrasta nem sobre um vídeo
    LaunchedEffect(active, paused, pager.settledPage, pager.isScrollInProgress) {
        if (!autoAdvance || !active || paused || images.size < 2 || pager.isScrollInProgress || isVideo(pager.settledPage))
            return@LaunchedEffect
        delay(SLIDE_MS)
        val next = pager.settledPage + 1
        if (next < images.size) pager.animateScrollToPage(next) else pager.scrollToPage(0)
    }
    LaunchedEffect(active) { if (!active && pager.currentPage != 0) pager.scrollToPage(0) }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { i ->
            val tapToPause = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            if (isVideo(i)) SlideVideo(images[i], playing = active && !paused && pager.settledPage == i, tapToPause)
            else AsyncImage(File(images[i]), null, tapToPause, contentScale = TikTokScale)
        }
        if (images.size > 1) {
            Text(
                "${pager.currentPage + 1}/${images.size}", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .45f))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(images.size) { i ->
                    Box(Modifier.size(5.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = .4f)))
                }
            }
        }
        PausedIcon(active && paused, Modifier.align(Alignment.Center), hidden = zoomed)
    }
}

// ───────────────────────── Linha do tempo (igual ao TikTok) ─────────────────────────

internal fun Long.clock(): String = (this / 1000).let { "%d:%02d".format(it / 60, it % 60) }

/**
 * Barra fina embaixo; ao tocar ou arrastar ela engrossa, ganha a bolinha e o vídeo acompanha o dedo.
 * Durante o arraste o seek vai para o keyframe mais próximo (instantâneo, sem travar); ao soltar
 * faz o seek exato. [fraction] é lido só no desenho: a barra redesenha sem recompor a tela.
 */
@Composable
internal fun TimelineBar(
    player: ExoPlayer, fraction: () -> Float, modifier: Modifier = Modifier,
    extraPx: Float = 0f,                                       // px a mais na espessura da barra (feed)
    onScrub: (Float?) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var resume by remember { mutableStateOf(false) }
    var exactAt by remember { mutableFloatStateOf(-1f) }        // última posição com seek exato
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }
    val thickness by animateDpAsState(if (dragging) 6.dp else 2.dp, label = "barra")

    fun seekTo(f: Float, exact: Boolean) {
        val d = player.duration.takeIf { it > 0 } ?: return
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo((d * f).toLong())
        exactAt = if (exact) f else -1f
    }
    /** Arrastando: keyframe mais próximo na hora (fluido); parou o dedo por 80 ms: quadro exato. */
    fun move(f: Float) {
        dragFrac = f; onScrub(f); seekTo(f, exact = false)
        settle?.cancel()
        settle = scope.launch { delay(80); seekTo(f, exact = true) }
    }
    fun start(f: Float) {
        dragging = true; resume = player.playWhenReady; player.pause()
        move(f)
    }
    fun end() {
        if (!dragging) return
        settle?.cancel()
        // se o quadro exato já está na tela, não faz outro seek (outro seek = redesenho = piscada)
        if (exactAt != dragFrac) seekTo(dragFrac, exact = true)
        player.setSeekParameters(SeekParameters.EXACT)
        if (resume) player.play()
        onScrub(null); dragging = false
    }

    Box(
        modifier.fillMaxWidth().height(28.dp)
            .pointerInput(player) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> start((o.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { end() }, onDragCancel = { end() },
                    onHorizontalDrag = { ch, _ -> ch.consume(); move((ch.position.x / size.width).coerceIn(0f, 1f)) }
                )
            }
            .pointerInput(player) {
                detectTapGestures { o -> val f = (o.x / size.width).coerceIn(0f, 1f); start(f); end() }
            }
            .drawBehind {
                val h = thickness.toPx() + extraPx
                val y = size.height - h / 2 - 2.dp.toPx()
                val f = if (dragging) dragFrac else fraction()
                drawLine(Color.White.copy(alpha = .28f), Offset(0f, y), Offset(size.width, y), h)
                drawLine(Color.White.copy(alpha = if (dragging) 1f else .85f), Offset(0f, y), Offset(size.width * f, y), h)
                if (dragging) drawCircle(Color.White, 7.dp.toPx(), Offset(size.width * f, y))
            }
    )
}

// ───────────────────────── Pager vertical (TikTok / Reels) ─────────────────────────

/**
 * 3 players reutilizados em rodízio (página % 3): nada é criado/destruído durante o swipe.
 * A página atual, a de cima e a de baixo ficam preparadas e pausadas no 1º frame.
 * Posts de fotos usam o mesmo player para a música.
 */
@Composable
fun FeedPager(profile: Profile, posts: List<Post>, startKey: String, nav: NavController, onDelete: (Post) -> Unit) {
    WhiteStatusBarIcons()
    val ctx = LocalContext.current
    val postsState = rememberUpdatedState(posts)
    val pager = rememberPagerState(initialPage = posts.indexOfFirst { it.key == startKey }.coerceAtLeast(0)) { postsState.value.size }
    // Página "da vez": ao soltar o dedo já é a página de destino (o vídeo começa a tocar durante a
    // animação de encaixe, igual ao TikTok); com o dedo na tela continua a atual.
    var fingerDown by remember { mutableStateOf(false) }
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> fingerDown = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> fingerDown = false
            }
        }
    }
    val settled = if (fingerDown) pager.settledPage else pager.targetPage
    // TikTok: volume normalizado entre vídeos (ganho medido por arquivo, aplicado no áudio de cada player)
    val normalize = profile.platform == "tiktok"
    val gains = remember { List(3) { GainProcessor() } }
    val pool = remember { List(3) { buildPlayer(ctx, loop = true, gain = if (normalize) gains[it] else null) } }
    val scope = rememberCoroutineScope()
    var slideSound by remember { mutableStateOf(false) }         // foto atual do carrossel é um vídeo com som próprio
    val slideSoundS by rememberUpdatedState(slideSound)
    var sheetOpen by remember { mutableStateOf(false) }          // folha da música
    var sheetPost by remember { mutableStateOf<Post?>(null) }
    val assignedKeys = remember { mutableStateListOf<String?>(null, null, null) }
    val sizes = remember { mutableStateMapOf<String, IntSize>() }
    var userPaused by remember { mutableStateOf(false) }
    var fast by remember { mutableStateOf(false) }                 // segurando a tela: 2x
    var scrub by remember { mutableStateOf<Float?>(null) }         // arrastando a linha do tempo
    var progress by remember { mutableFloatStateOf(0f) }
    val owner = LocalLifecycleOwner.current
    val userPausedS by rememberUpdatedState(userPaused)
    val settledS by rememberUpdatedState(settled)

    DisposableEffect(owner) {
        val listeners = pool.mapIndexed { i, p ->
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    val k = assignedKeys[i] ?: return
                    videoSize.displaySize()?.let { sizes[k] = it }
                }
            }.also { p.addListener(it) }
        }
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> pool.forEach { it.pause() }
                Lifecycle.Event.ON_RESUME ->
                    if (!userPausedS && !slideSoundS && postsState.value.getOrNull(settledS)?.playable == true) pool[settledS % 3].play()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose {
            owner.lifecycle.removeObserver(o)
            pool.forEachIndexed { i, p -> p.removeListener(listeners[i]); p.release() }
        }
    }

    LaunchedEffect(settled, posts.getOrNull(settled)?.key) {
        fun assign(i: Int) {
            val post = postsState.value.getOrNull(i) ?: return
            val slot = i % 3
            val pl = pool[slot]
            if (!post.playable) {
                if (assignedKeys[slot] != null) { pl.stop(); pl.clearMediaItems(); assignedKeys[slot] = null }
                return
            }
            if (assignedKeys[slot] != post.key) {
                if (normalize) {
                    val path = post.mediaPath
                    val known = Loudness.known(path)
                    gains[slot].gain = known?.let(Loudness::gainOf) ?: 1f
                    if (known == null) scope.launch(Dispatchers.IO) {
                        val g = Loudness.gainOf(Loudness.rms(path))
                        if (assignedKeys[slot] == post.key) gains[slot].gain = g
                    }
                }
                pl.setMediaItem(mediaItemOf(post))
                pl.playWhenReady = false
                pl.prepare()
                assignedKeys[slot] = post.key
            }
        }
        userPaused = false; fast = false; scrub = null; slideSound = false
        pool.forEach { it.setPlaybackSpeed(1f) }
        assign(settled)                                        // a atual primeiro: começa a tocar já
        pool.forEachIndexed { s, p -> if (s != settled % 3) p.pause() }
        // seek só se precisar: cada seek esvazia o decodificador e redesenha o quadro
        if (postsState.value.getOrNull(settled)?.playable == true) pool[settled % 3].apply { if (currentPosition > 0) seekTo(0); play() }
        delay(150)                                             // só então prepara os vizinhos (não disputa o decoder)
        assign(settled + 1); assign(settled - 1)
        for (i in listOf(settled - 1, settled + 1))
            if (postsState.value.getOrNull(i)?.playable == true) pool[i % 3].apply { pause(); if (currentPosition > 0) seekTo(0) }
    }

    // carrossel: vídeo com som próprio pausa a música do post; foto ou vídeo sem som, ela continua
    LaunchedEffect(slideSound) {
        val post = postsState.value.getOrNull(settledS) ?: return@LaunchedEffect
        if (!post.isSlideshow || !post.playable) return@LaunchedEffect
        val p = pool[settledS % 3]
        if (slideSound) p.pause() else if (!userPausedS) p.play()
    }

    // o perfil, ao voltar, rola até o último vídeo visto aqui
    LaunchedEffect(settled) {
        posts.getOrNull(settled)?.key?.let { nav.previousBackStackEntry?.savedStateHandle?.set("lastPost", it) }
    }

    // progresso lido a cada frame só enquanto um vídeo está na tela (redesenha só a barra)
    LaunchedEffect(settled, posts.getOrNull(settled)?.isVideo) {
        progress = 0f
        if (postsState.value.getOrNull(settled)?.isVideo != true) return@LaunchedEffect
        val pl = pool[settled % 3]
        while (isActive) {
            withFrameMillis { }
            val d = pl.duration
            progress = if (d > 0) (pl.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
        }
    }

    val onTap: () -> Unit = {
        val p = pool[settled % 3]
        if (slideSound) userPaused = !userPaused            // vídeo do carrossel com som: a música fica parada
        else if (p.playWhenReady) { p.pause(); userPaused = true } else { p.play(); userPaused = false }
    }
    val onHold: (Boolean) -> Unit = { on ->
        if (on != fast) {
            fast = on
            pool[settled % 3].apply { setPlaybackSpeed(if (on) 2f else 1f); if (on) play() }
            if (on) userPaused = false
        }
    }
    // Sair puxando além das pontas: no 1º vídeo puxando para o anterior, no último puxando para o próximo
    // (no meio do perfil o puxão só troca de vídeo). O voltar do sistema continua funcionando.
    val exitPx = with(LocalDensity.current) { 110.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }        // deslocamento do feed além da ponta
    val limitPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() } / 4f   // 25% da tela
    val exitConnection = remember(nav) {
        object : NestedScrollConnection {
            // dedo VOLTANDO depois de puxar: desfaz o puxão (1:1) antes de o pager rolar.
            // Antes isto pegava qualquer movimento com o puxão ativo, inclusive continuar puxando,
            // e aí a resistência nunca era aplicada: a faixa preta seguia o dedo até o meio da tela.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull == 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y * pull > 0f) return Offset.Zero        // mesma direção do puxão: vai para onPostScroll
                val next = pull + available.y
                val used = (if (pull > 0) next.coerceAtLeast(0f) else next.coerceAtMost(0f)) - pull
                pull += used
                return Offset(0f, used)
            }
            // o que o pager não usou só sobra nas pontas: vira puxão. Até 25% da tela segue o dedo pela metade;
            // passando disso a resistência cresce rápido (só uma "puxadinha", como no limite de rolagem do Android).
            // A resistência fica no dedo, não no desenho: voltar o dedo desfaz o puxão na hora, sem trecho morto.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                val over = (abs(pull) - limitPx).coerceAtLeast(0f)
                val factor = if (over == 0f) 0.5f else 0.12f / (1f + over / (limitPx * 0.15f))
                pull += available.y * factor
                return available
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull == 0f) return Velocity.Zero
                if (abs(pull) >= exitPx) nav.popBackStack()
                else animate(pull, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> pull = v }
                return available
            }
        }
    }

    val currentPost = posts.getOrNull(pager.currentPage)
    val settledPlayer = pool[settled % 3]
    // página ampliada com pinça: trava o swipe e esconde a interface; voltar desfaz o zoom em vez de sair
    var zoomedKey by remember { mutableStateOf<String?>(null) }
    val zoomedPage = zoomedKey != null
    var resetZoom by remember { mutableIntStateOf(0) }
    BackHandler(zoomedPage) { resetZoom++ }
    val showTimeline = posts.getOrNull(settled)?.isVideo == true && !zoomedPage

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            // o feed só desliza (sem esticar) e deixa a faixa preta do fundo aparecer; soltou além de 110 dp, sai do vídeo
            pager, Modifier.fillMaxSize().nestedScroll(exitConnection).graphicsLayer { translationY = pull },
            beyondViewportPageCount = 1,
            userScrollEnabled = scrub == null && !zoomedPage && !sheetOpen,
            flingBehavior = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(1)),
            key = { postsState.value.getOrNull(it)?.key ?: it }
        ) { page ->
            val post = posts.getOrNull(page) ?: return@VerticalPager
            val slot = page % 3
            val near = abs(page - settled) <= 1
            val player = if (post.isVideo && near && assignedKeys[slot] == post.key) pool[slot] else null
            FeedPage(profile, post, player, sizes[post.key], page == settled, userPaused,
                hideCaption = page == settled && scrub != null, onTap = onTap, onHold = onHold,
                resetZoom = resetZoom, onZoom = { z -> if (z) zoomedKey = post.key else if (zoomedKey == post.key) zoomedKey = null },
                onMusic = { sheetPost = post; sheetOpen = true },
                onSlideSound = { on -> if (page == settled) slideSound = on })
        }

        if (!zoomedPage) IconButton({ nav.popBackStack() }, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White)
        }
        if (currentPost != null && !zoomedPage) PostMenu(profile, currentPost, Color.White,
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp), onDelete)

        // "2x" enquanto segura a tela
        if (fast) Text("2x  ▸▸", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp)
                .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .45f))
                .padding(horizontal = 14.dp, vertical = 6.dp))

        // tempo grande no centro-baixo enquanto arrasta a linha do tempo
        scrub?.let { f ->
            val d = settledPlayer.duration.coerceAtLeast(0)
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp)) {
                Text((d * f).toLong().clock(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("  /  ${d.clock()}", color = Color.White.copy(alpha = .6f), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showTimeline) TimelineBar(
            settledPlayer, { progress },
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp).offset { IntOffset(0, -1) },
            extraPx = 2f,
            // ao soltar, a barra continua no ponto solto (o progresso antigo era o do keyframe)
            onScrub = { f -> if (f == null) scrub?.let { progress = it }; scrub = f }
        )

        // folha da música por cima de tudo (o post fica guardado para a animação de saída)
        FeedSheet(sheetOpen, { sheetOpen = false }) { sheetPost?.let { MusicInfo(profile, it) } }
    }
}
