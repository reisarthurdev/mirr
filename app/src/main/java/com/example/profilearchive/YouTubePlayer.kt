@file:OptIn(UnstableApi::class)

package com.example.profilearchive

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

// ───────────────────────── Posição salva de cada vídeo ─────────────────────────

/**
 * Onde parou o ÚLTIMO vídeo assistido (só ele: abrir outro vídeo substitui).
 * Perto do fim conta como assistido e volta do começo.
 */
object PlaybackMemory {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("positions", Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String): Long =
        sp(ctx).takeIf { it.getString("key", null) == key }?.getLong("pos", 0L) ?: 0L
    fun save(ctx: Context, key: String, position: Long, duration: Long) {
        val done = duration > 0 && position >= duration - 3_000
        sp(ctx).edit().clear().apply { if (!done && position >= 1_000) putString("key", key).putLong("pos", position) }.apply()
    }
}

// ───────────────────────── Controles do player (YouTube e X) ─────────────────────────

/**
 * Controles próprios no lugar dos do PlayerView: sem engrenagem (áudio/velocidade) e sem anterior/próximo
 * ou ±10 s. Toque mostra/esconde; dois toques na metade esquerda/direita voltam/avançam 5 s.
 * O play/pause só existe enquanto está visível: não dá para pausar tocando numa tela "vazia".
 * Arrastar para baixo chama [onMinimize] (miniplayer), acompanhando o dedo por [drag].
 */
@Composable
fun VideoControls(
    player: ExoPlayer, fullscreen: Boolean,
    onFullscreen: (() -> Unit)?, onMinimize: (() -> Unit)?, drag: MutableFloatState?,
    topEnd: (@Composable () -> Unit)? = null           // ex.: menu ⋮, aparece e some junto com os controles
) {
    var visible by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var ended by remember { mutableStateOf(player.playbackState == Player.STATE_ENDED) }
    var poke by remember { mutableIntStateOf(0) }                    // qualquer interação reinicia o timer
    var hint by remember { mutableStateOf<Pair<Int, Int>?>(null) }   // lado (-1/1) e nº do toque
    var progress by remember { mutableFloatStateOf(0f) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val minimizePx = with(LocalDensity.current) { 110.dp.toPx() }
    val minimize by rememberUpdatedState(onMinimize)         // o gesto usa sempre a versão atual do callback

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                if (ended) visible = true
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    // some sozinho 3 s depois da última interação, se estiver tocando
    LaunchedEffect(visible, playing, poke, scrub) {
        if (visible && playing && scrub == null) { delay(3_000); visible = false }
    }
    // progresso a cada frame só enquanto os controles aparecem
    LaunchedEffect(visible) {
        while (visible && isActive) {
            withFrameMillis { }
            val d = player.duration
            progress = if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
        }
    }
    LaunchedEffect(hint) { if (hint != null) { delay(650); hint = null } }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(player) {
                detectTapGestures(
                    onTap = { visible = !visible; poke++ },
                    onDoubleTap = { o ->
                        val dir = if (o.x < size.width / 2f) -1 else 1
                        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + dir * 5_000L).coerceIn(0L, end))
                        // toques seguidos do mesmo lado somam (5 s, 10 s, 15 s…)
                        hint = dir to (if (hint?.first == dir) hint!!.second + 1 else 1)
                    }
                )
            }
            .then(
                if (drag != null && onMinimize != null && !fullscreen) Modifier.pointerInput(player) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (drag.floatValue > minimizePx) minimize?.invoke(); drag.floatValue = 0f },
                        onDragCancel = { drag.floatValue = 0f }
                    ) { ch, dy -> ch.consume(); drag.floatValue = (drag.floatValue + dy).coerceAtLeast(0f) }
                } else Modifier
            )
    ) {
        // aviso de ±5 s: entra crescendo, dá um "pulo" a cada toque e sai sumindo.
        // O último valor fica guardado para a animação de saída ainda ter o que mostrar.
        val lastHint = remember { arrayOf(1 to 1) }
        hint?.let { lastHint[0] = it }
        val (hintDir, hintTaps) = hint ?: lastHint[0]
        val bump = remember { Animatable(1f) }
        LaunchedEffect(hint) {
            if (hint != null) { bump.snapTo(1.2f); bump.animateTo(1f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessMediumLow)) }
        }
        AnimatedVisibility(hint != null,
            Modifier.align(if (hintDir < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 36.dp),
            enter = fadeIn(tween(120)) + scaleIn(tween(180), initialScale = .6f),
            exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = .85f)) {
            Text(if (hintDir < 0) "« ${hintTaps * 5} s" else "${hintTaps * 5} s »", color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.graphicsLayer { scaleX = bump.value; scaleY = bump.value }
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .45f)).padding(horizontal = 14.dp, vertical = 6.dp))
        }
        AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f))) {
                IconButton({
                    when {
                        ended -> { player.seekTo(0); player.play() }
                        player.isPlaying -> player.pause()
                        else -> player.play()
                    }
                    poke++
                }, Modifier.align(Alignment.Center).size(72.dp)) {
                    Icon(painterResource(if (playing) androidx.media3.ui.R.drawable.exo_styled_controls_pause
                        else androidx.media3.ui.R.drawable.exo_styled_controls_play),
                        if (playing) "Pausar" else "Reproduzir", Modifier.size(52.dp), tint = Color.White)
                }
                topEnd?.let { Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp)) { it() } }   // afastado da borda
                val d = player.duration.coerceAtLeast(0)
                TimelineBar(player, { scrub ?: progress }, Modifier.align(Alignment.BottomCenter)) { f -> scrub = f; poke++ }
                // colado na barra (antes ficava ~24 dp acima e parecia flutuar). Vem depois da barra para o
                // botão de tela cheia ganhar o toque onde os dois se sobrepõem; o texto deixa o toque passar.
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${(d * (scrub ?: progress)).toLong().clock()} / ${d.clock()}", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    onFullscreen?.let {
                        IconButton({ it(); poke++ }) {
                            Icon(painterResource(if (fullscreen) androidx.media3.ui.R.drawable.exo_styled_controls_fullscreen_exit
                                else androidx.media3.ui.R.drawable.exo_styled_controls_fullscreen_enter), "Tela cheia", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Player do YouTube dentro do canal ─────────────────────────

/**
 * Vídeo aberto em cada canal. Fica guardado enquanto o app roda: saindo do canal com o miniplayer
 * aberto, ao voltar ele reaparece no mesmo ponto, pausado.
 */
object YtSessions {
    /** corner do miniplayer: 0 sup. esq., 1 sup. dir., 2 inf. esq., 3 inf. dir. (lembrado por canal) */
    data class Session(val postKey: String, val expanded: Boolean, val autoPlay: Boolean, val corner: Int = 3)
    val byProfile = mutableStateMapOf<Long, Session>()
    fun open(profileId: Long, key: String) {
        byProfile[profileId] = Session(key, expanded = true, autoPlay = true, corner = byProfile[profileId]?.corner ?: 3)
    }
}

/**
 * Player do YouTube por cima da tela do canal: aberto (player + detalhes) ou miniplayer flutuante.
 * Um único PlayerView em todos os modos (mesmo ponto da composição): trocar de modo não recria a
 * superfície do vídeo, então não pisca.
 */
@Composable
fun YouTubeHost(profile: Profile, posts: List<Post>, onDelete: (Post) -> Unit) {
    val session = YtSessions.byProfile[profile.id] ?: return
    val post = posts.firstOrNull { it.key == session.postKey }
    if (post == null) {                                     // vídeo apagado: fecha o player
        if (posts.isNotEmpty()) LaunchedEffect(Unit) { YtSessions.byProfile.remove(profile.id) }
        return
    }
    val ctx = LocalContext.current
    val act = ctx.findActivity()
    val view = LocalView.current
    val density = LocalDensity.current
    val player = rememberPlayer(loop = false)              // pausa ao sair do app; liberado ao sair do canal
    val expanded = session.expanded
    var fs by rememberSaveable { mutableStateOf(false) }
    val drag = remember { mutableFloatStateOf(0f) }
    var expandedText by remember { mutableStateOf(false) }
    fun update(f: (YtSessions.Session) -> YtSessions.Session) {
        YtSessions.byProfile[profile.id]?.let { YtSessions.byProfile[profile.id] = f(it) }
    }
    fun savePosition() = runCatching { PlaybackMemory.save(ctx, post.key, player.currentPosition, player.duration) }

    // carrega do ponto em que parou; só toca sozinho quando acabou de ser aberto (voltando ao canal fica pausado)
    LaunchedEffect(post.key) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(post.mediaPath))), PlaybackMemory.get(ctx, post.key))
        player.prepare()
        player.playWhenReady = session.autoPlay
        if (session.autoPlay) update { it.copy(autoPlay = false) }
    }
    // guarda a posição a cada 5 s, ao pausar e ao sair
    LaunchedEffect(post.key) { while (isActive) { delay(5_000); if (player.isPlaying) savePosition() } }
    DisposableEffect(post.key) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) savePosition() }
        }
        player.addListener(l)
        onDispose { savePosition(); player.removeListener(l) }
    }

    val ratio = remember(post.key) {
        val w = post.width ?: 0; val h = post.height ?: 0
        if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(9f / 16f, 21f / 9f) else 16f / 9f
    }

    // voltar: sai da tela cheia; aberto vira miniplayer; com o mini, o voltar é o normal do app
    BackHandler(expanded) { if (fs) fs = false else update { it.copy(expanded = false) } }
    LaunchedEffect(expanded) { if (!expanded) fs = false }
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
    if (expanded) WhiteStatusBarIcons()

    val scope = rememberCoroutineScope()
    // k: 0 = player aberto, 1 = miniplayer. Animado entre os dois (e acompanhando o dedo ao arrastar para baixo).
    var t by remember { mutableFloatStateOf(if (expanded) 0f else 1f) }
    LaunchedEffect(expanded) {
        // Mola criticamente amortecida: acompanha a velocidade de onde o dedo soltou (natural em qualquer canto)
        // e nunca passa do ponto (passar do ponto fazia o fundo aparecer/sumir no canto = piscar)
        animate(t, if (expanded) 0f else 1f, animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> t = v }
    }
    // fechar: encolhe e some, e só então tira o player do canal
    var closing by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(if (closing) 0f else 1f, tween(220), label = "fechar") {
        if (closing) YtSessions.byProfile.remove(profile.id)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
        val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat()
        val playerPx = with(density) { minOf(maxWidth / ratio, maxHeight * 0.6f).toPx() }   // altura do player aberto
        // o mini tem a MESMA proporção da área do player aberto: a transição é só escala + posição
        val miniW = with(density) { (if (w / playerPx >= 1f) 208.dp else 140.dp).toPx() }
        val miniH = miniW * playerPx / w
        val margin = with(density) { 12.dp.toPx() }

        /** canto da tela onde o miniplayer gruda (igual ao YouTube) */
        fun cornerPos(c: Int) = Offset(
            if (c % 2 == 0) margin else w - miniW - margin,
            if (c < 2) statusTop + margin else h - miniH - margin - bottomInset
        )
        // Posição/canto do mini e progresso da transição são lidos SÓ dentro de graphicsLayer/offset (fase de
        // desenho). Antes eram lidos aqui na composição: cada frame do arrasto e do arremesso recompunha o
        // player inteiro, e o frame que atrasava aparecia como um piscar. O canto vai para a sessão só ao sair.
        var corner by remember { mutableIntStateOf(session.corner) }
        DisposableEffect(Unit) { onDispose { update { it.copy(corner = corner) } } }
        val dragPos = remember { mutableStateOf<Offset?>(null) }        // posição enquanto arrasta/volta ao canto
        var snapJob by remember { mutableStateOf<Job?>(null) }          // animação de grudar no canto em andamento
        fun miniPos() = dragPos.value ?: cornerPos(corner)
        // distância do arrasto até virar mini: no mínimo 40% da tela, mesmo com o mini no canto de cima
        fun travel() = maxOf(miniPos().y - statusTop, h * 0.4f)
        /** 0 = aberto, 1 = mini (acompanha o dedo ao arrastar para baixo) */
        fun kNow(): Float = when {
            fs -> 0f
            expanded -> maxOf(t, (drag.floatValue / travel()).coerceIn(0f, 1f))
            else -> t
        }.coerceIn(0f, 1f)
        val showDetails by remember(fs, expanded, w, h, playerPx) { derivedStateOf { kNow() < 0.999f } }
        val showClose by remember { derivedStateOf { t > 0.95f } }

        // fundo + detalhes (somem conforme vira mini); bloqueia toques na tela do canal por trás
        if (showDetails) Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = 1f - kNow() }
                .background(if (fs) Color.Black else MaterialTheme.colorScheme.background)
                .then(if (expanded) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier)
        ) {
            if (!fs) Column(
                Modifier.fillMaxSize().statusBarsPadding().padding(top = with(density) { playerPx.toDp() })
                    .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp)
            ) {
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
                    Modifier.fillMaxWidth().clickable { expandedText = !expandedText },
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Text(post.description, Modifier.padding(12.dp), maxLines = if (expandedText) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        val cornerDp = with(density) { 12.dp.toPx() }
        val tracker = remember { VelocityTracker() }

        // O player tem SEMPRE o tamanho do modo aberto (ou da tela cheia) e vira mini só por escala + posição
        // na GPU (graphicsLayer). Antes o tamanho mudava a cada frame: o vídeo era redimensionado o tempo
        // todo durante a transição e ao arrastar o mini, e isso causava os piscares.
        Box(
            Modifier
                .layout { m, _ ->
                    val bw = w.roundToInt(); val bh = (if (fs) h else playerPx).roundToInt()
                    val p = m.measure(Constraints.fixed(bw, bh))
                    layout(p.width, p.height) { p.place(0, if (fs) 0 else statusTop.roundToInt()) }
                }
                .graphicsLayer {
                    val k = kNow()
                    val pos = miniPos()
                    // Mini no canto de cima: o player já está em cima, então só encolher pareceria seco. Ele faz
                    // um arco (desce um pouco e sobe até o canto), como se fosse "jogado" para lá.
                    val arc = if (corner < 2 && !fs) kotlin.math.sin(k * Math.PI).toFloat() * h * 0.12f else 0f
                    val boxLeft = if (fs) 0f else lerp(0f, pos.x, k)
                    val boxTop = if (fs) 0f else lerp(statusTop, pos.y, k) + arc
                    val boxScale = if (fs) 1f else lerp(1f, miniW / w, k)
                    val cornerPx = cornerDp * k                                // canto arredondado na tela
                    val c = 0.6f + 0.4f * shown                               // fechar: encolhe para o centro e some
                    val sc = boxScale * c
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = sc; scaleY = sc
                    translationX = boxLeft + size.width * boxScale * (1f - c) / 2f
                    translationY = boxTop - (if (fs) 0f else statusTop) + size.height * boxScale * (1f - c) / 2f
                    alpha = shown
                    shadowElevation = 10.dp.toPx() * k
                    shape = RoundedCornerShape(cornerPx / sc.coerceAtLeast(0.01f)); clip = true
                }
                .background(Color.Black)
        ) {
            AndroidView(
                { c -> inflatePlayer(c, false) }, Modifier.fillMaxSize(),
                update = { v ->
                    if (v.player !== player) {
                        v.player = player
                        if (!player.isPlaying && player.playbackState != Player.STATE_IDLE) player.seekTo(player.currentPosition)
                    }
                },
                onRelease = { it.player = null }
            )
            if (expanded) Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - kNow() * 3f).coerceIn(0f, 1f) }) {
                VideoControls(player, fs, onFullscreen = { fs = !fs },
                    onMinimize = { t = (drag.floatValue / travel()).coerceIn(0f, 1f); update { it.copy(expanded = false) } }, drag = drag,
                    topEnd = if (fs) null else ({ PostMenu(profile, post, Color.White, onDelete = onDelete) }))
            }
        }

        // Miniplayer: camada de toque por cima (sem escala), no lugar do mini.
        // Arrastar move; ao soltar gruda no canto para onde foi jogado; tocar abre; X fecha.
        if (!expanded && !closing) Box(
            Modifier
                .offset { miniPos().let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                .size(with(density) { miniW.toDp() }, with(density) { miniH.toDp() })
                .pointerInput(w, h, miniW) {
                    detectDragGestures(
                        // pegar o mini no meio de uma animação: para a animação e continua de onde ele está
                        onDragStart = {
                            snapJob?.cancel(); snapJob = null
                            tracker.resetTracking()
                            dragPos.value = miniPos()
                        },
                        onDragEnd = {
                            val from = dragPos.value ?: return@detectDragGestures
                            val v = tracker.calculateVelocity()
                            val aim = from + Offset(v.x, v.y) * 0.2f            // projeta o arremesso
                            val c = (if (aim.y + miniH / 2 < h / 2) 0 else 2) + (if (aim.x + miniW / 2 < w / 2) 0 else 1)
                            val target = cornerPos(c)
                            snapJob = scope.launch {
                                // mola que parte com a velocidade do arremesso: sem "freada" no começo do encaixe
                                val dist = (target - from).getDistance().coerceAtLeast(1f)
                                val v0 = (v.x * (target.x - from.x) + v.y * (target.y - from.y)) / (dist * dist)
                                animate(0f, 1f, initialVelocity = v0.coerceIn(0f, 20f),
                                    animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) { f, _ ->
                                    dragPos.value = lerp(from, target, f)
                                }
                                corner = c; dragPos.value = null                // troca no mesmo frame: posição igual
                            }
                        },
                        onDragCancel = { dragPos.value = null }
                    ) { ch, d ->
                        ch.consume()
                        val p = miniPos() + d
                        val clamped = Offset(p.x.coerceIn(margin, w - miniW - margin),
                            p.y.coerceIn(statusTop + margin, h - miniH - margin - bottomInset))
                        dragPos.value = clamped
                        tracker.addPosition(ch.uptimeMillis, clamped)
                    }
                }
                .pointerInput(Unit) { detectTapGestures { update { it.copy(expanded = true) } } }
        ) {
            if (showClose) IconButton({ savePosition(); player.pause(); closing = true },
                Modifier.align(Alignment.TopEnd).size(36.dp)) {
                Icon(Icons.Default.Close, "Fechar", tint = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .45f)).padding(4.dp))
            }
        }
    }
}
