@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.profilearchive

import kotlinx.coroutines.coroutineScope
import androidx.compose.foundation.interaction.DragInteraction
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.flow.first

import android.net.Uri
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.animate
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.BackEventCompat
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.lifecycle.SavedStateHandle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ───────────────────────── Gestos e utilidades ─────────────────────────

/**
 * Arrastar para a direita em qualquer ponto da tela volta à anterior, com a MESMA animação interativa do
 * gesto de voltar do sistema (predictive back): o arrasto é repassado ao OnBackPressedDispatcher, e o
 * NavHost desliza esta tela junto com o dedo mostrando a anterior por baixo.
 *
 * Antes a tela era só deslocada; a anterior não existia por trás durante o arrasto e só aparecia ao soltar,
 * já no meio da animação: era o "piscar" ao voltar para a tela inicial.
 */
fun Modifier.swipeToBack(onBack: () -> Unit): Modifier = composed {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val density = LocalDensity.current
    val width = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val threshold = with(density) { 110.dp.toPx() }
    val back by rememberUpdatedState(onBack)
    var dx by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf(false) }

    fun event(progress: Float) = BackEventCompat(dx, 0f, progress, BackEventCompat.EDGE_LEFT)

    draggable(
        rememberDraggableState { d ->
            dx = (dx + d).coerceAtLeast(0f)
            if (active) dispatcher?.dispatchOnBackProgressed(event((dx / width).coerceIn(0f, 1f)))
        },
        Orientation.Horizontal,
        onDragStarted = {
            dx = 0f
            active = dispatcher != null
            dispatcher?.dispatchOnBackStarted(event(0f))
        },
        onDragStopped = { v ->
            val go = dx > threshold || v > 1600f
            when {
                !active -> if (go) back()
                go -> dispatcher?.onBackPressed()                 // completa a animação a partir de onde o dedo parou
                else -> dispatcher?.dispatchOnBackCancelled()     // volta a tela ao lugar
            }
            active = false; dx = 0f
        }
    )
}

@Composable
fun ObserveResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val cb by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) cb() }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
}

@Composable
fun Avatar(path: String?, size: Int) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.dp.roundToPx() }
    // tamanho fixo no pedido: com a foto no cache de memória o Coil já desenha no 1º frame (sem piscar)
    val req = remember(path, px) { path?.let { ImageRequest.Builder(ctx).data(File(it)).size(px).build() } }
    AsyncImage(req, null,
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentScale = ContentScale.Crop)
}

/** Miniatura: capa baixada; post de fotos sem capa usa a 1ª foto; foto simples usa ela mesma. */
fun thumbOf(p: Post): File? =
    // vídeo sem capa baixada: o próprio arquivo (o Coil tira um quadro dele, ver MainActivity)
    (p.thumbPath?.takeIf { File(it).exists() } ?: p.slides?.substringBefore('\n') ?: p.mediaPath).let { File(it) }
/** "@usuario"; canal do YouTube salvo pelo id (UC…) mostra o nome do canal no lugar do id ilegível. */
fun Profile.handle(): String = when {
    platform == "youtube" && username.startsWith("UC") && username.length == 24 -> displayName
    platform == "reddit" -> Reddit.handle(username)
    else -> "@$username"
}

fun platformLabel(p: String) = when (p) { "youtube" -> "YouTube"; "tiktok" -> "TikTok"; "instagram" -> "Instagram"; "reddit" -> "Reddit"; else -> "X" }
fun Long.date(): String = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(this))
fun Long.compact(): String = when {
    this >= 1_000_000 -> "%.1fM".format(this / 1e6)
    this >= 1_000 -> "%.1fK".format(this / 1e3)
    else -> toString()
}

@Composable
fun WorkProgress(w: WorkInfo, modifier: Modifier = Modifier) {
    val pr = w.progress
    val n = pr.getInt("n", 0); val d = pr.getInt("d", 0)
    val overall = if (n > 0) (d.toFloat() / n).coerceIn(0f, 1f) else null
    val text = when {
        w.state == WorkInfo.State.ENQUEUED -> "Na fila…"
        n > 0 -> "Baixando $d de $n · ${d * 100 / n}%"
        else -> pr.getString("line") ?: "Listando conteúdo…"
    }
    Column(modifier) {
        if (overall != null) LinearProgressIndicator({ overall.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun ConfirmDeleteMedia(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null) },
        title = { Text("Excluir esta mídia?") },
        text = { Text("O arquivo será apagado do dispositivo e não será baixado de novo nas atualizações.") },
        confirmButton = { TextButton(onConfirm) { Text("Excluir") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } }
    )
}

// ───────────────────────── Navegação + menu lateral ─────────────────────────

/** Mesma curva e duração em todas as transições de tela. */
private fun <T> navTween() = tween<T>(360, easing = FastOutSlowInEasing)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val entry = nav.currentBackStackEntryAsState()
    // Engasgo no fim da abertura: ler drawer.isOpen/route direto aqui recompunha a tela INTEIRA
    // (NavHost + lista de perfis) no instante em que a aba terminava de abrir, e esse frame atrasava.
    // Com derivedStateOf só recompõe quando o resultado muda (na tela inicial ele é sempre true),
    // e os lambdas lembrados não fazem as telas recomporem por receber uma função "nova".
    val gestures by remember {
        derivedStateOf { entry.value?.destination?.route.let { it == "home" || it == "add" } || drawer.isOpen }
    }
    val openMenu: () -> Unit = remember { { scope.launch { drawer.open() } } }
    val go: (String) -> Unit = remember {
        { r ->
            scope.launch { drawer.close() }
            nav.navigate(r) { popUpTo("home"); launchSingleTop = true }
        }
    }
    // Rede de segurança do "voltar": fora da tela inicial ele nunca fecha o app. Registrado antes do
    // NavHost, só age se o voltar do NavHost não estiver ativo (aí o app fechava em vez de voltar uma tela).
    val notHome by remember { derivedStateOf { entry.value?.destination?.route.let { it != null && it != "home" } } }
    BackHandler(notHome) {
        if (!nav.popBackStack()) nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = gestures,
        drawerContent = {
            val route = entry.value?.destination?.route   // lido só aqui: muda só a aba
            ModalDrawerSheet(Modifier.width(320.dp)) {   // um pouco mais estreito que o padrão (360dp)
                Text(stringResource(R.string.app_name), Modifier.padding(28.dp, 24.dp), style = MaterialTheme.typography.titleLarge)
                val m = Modifier.padding(horizontal = 12.dp)
                NavigationDrawerItem({ Text("Perfis baixados") }, route == "home", { go("home") }, m, icon = { Icon(Icons.Default.Home, null) })
                NavigationDrawerItem({ Text("Adicionar perfil por link") }, route == "add", { go("add") }, m, icon = { Icon(Icons.Default.Add, null) })
                NavigationDrawerItem({ Text("Configurações") }, route == "settings", { go("settings") }, m, icon = { Icon(Icons.Default.Settings, null) })
            }
        }
    ) {
        NavHost(
            nav, "home",
            // Todas as telas: a nova entra deslizando da direita por cima da anterior (que recua um pouco);
            // ao voltar, a tela atual é "arrastada" para a direita até sumir e a anterior volta ao lugar.
            enterTransition = { slideInHorizontally(navTween()) { it } },
            exitTransition = { slideOutHorizontally(navTween()) { -it / 4 } },
            popEnterTransition = { slideInHorizontally(navTween()) { -it / 4 } },
            popExitTransition = { slideOutHorizontally(navTween()) { it } }
        ) {
            composable("home") { HomeScreen(nav, openMenu) }
            composable("add") { AddScreen(nav, openMenu) }
            composable("settings") { SettingsScreen(nav) }
            composable("profile/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                ProfileScreen(it.arguments!!.getLong("id"), nav, it.savedStateHandle)
            }
            composable("post/{key}") { PostScreen(it.arguments!!.getString("key")!!, nav) }
        }
    }
}

// ───────────────────────── Tela inicial ─────────────────────────

private const val FILLER_KEY = "overscroll-filler"

/**
 * O Compose só mostra o efeito de esticar quando a lista consegue rolar. Com poucos perfis a tela inicial
 * cabia inteira e não esticava. Este espaço vazio no fim completa a lista até 1 px além da tela:
 * ela passa a "rolar" (1 px, imperceptível) e o esticar aparece igual nas outras telas.
 * Calculado a partir dos outros itens, então nunca sobra rolagem de verdade em lista longa.
 */
@Composable
private fun rememberOverscrollFiller(list: LazyListState, spacing: Dp): Dp {
    val density = LocalDensity.current
    val px by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val items = info.visibleItemsInfo.filter { it.key != FILLER_KEY }
            // lista maior que a tela (há itens fora dela): não precisa de nada
            if (items.isEmpty() || info.totalItemsCount - 1 > items.size) 0
            else {
                val last = items.last()
                val end = last.offset + last.size + with(density) { spacing.roundToPx() }
                (info.viewportEndOffset - info.afterContentPadding - end + 1).coerceAtLeast(0)
            }
        }
    }
    return with(density) { px.toDp() }
}

/** Downloads que a tela inicial já viu (para subir ao topo só quando um download NOVO começa). */
private object HomeScroll {
    val seen = HashSet<java.util.UUID>()
    var ready = false
    val seenFailed = HashSet<java.util.UUID>()
    var readyFailed = false
}

@Composable
fun HomeScreen(nav: NavController, openMenu: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val wm = remember { WorkManager.getInstance(ctx) }
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val loaded by remember { HomeData.profiles(ctx) }.collectAsStateWithLifecycle()
    val profiles = loaded.orEmpty()
    val works by remember { HomeData.works(ctx) }.collectAsStateWithLifecycle()
    var base by remember { mutableStateOf(prefs.baseDir) }
    var access by remember { mutableStateOf(hasStorageAccess(ctx, base)) }
    ObserveResume { base = prefs.baseDir; access = hasStorageAccess(ctx, base) }
    // pasta nova ou acesso recém-concedido: prepara e procura perfis que já estejam nela
    LaunchedEffect(access, base) { if (access && prefs.importedDir != base) useFolder(ctx, base) }
    var removing by remember { mutableStateOf<Profile?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val failed = works.filter { it.state == WorkInfo.State.FAILED }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            ShrinkingTopBar("Perfis", scroll) { IconButton(openMenu) { Icon(Icons.Default.Menu, "Menu") } }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate("add") },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Adicionar") }
            )
        }
    ) { pad ->
        val list = rememberLazyListState()
        val filler = rememberOverscrollFiller(list, spacing = 10.dp)
        // download novo (perfil adicionado ou reverificado): volta a lista ao topo e abre o título de novo.
        // Os ids já vistos ficam fora da tela (HomeScroll): voltar para a tela inicial não conta como novo.
        val active = works.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }.map { it.id }
        suspend fun toTop() = coroutineScope {
            // voltando da tela "Adicionar" a lista ainda nem foi medida (e a posição salva é restaurada na
            // 1ª medida, desfazendo uma rolagem feita antes): espera ela ter itens e um quadro desenhado
            snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
            withFrameNanos { }
            launch { list.animateScrollToItem(0) }
            animate(scroll.state.heightOffset, 0f) { v, _ -> scroll.state.heightOffset = v }
            scroll.state.contentOffset = 0f
            // O perfil sobe na lista (ordem por atualização) só quando o worker grava a linha, depois daqui.
            // O LazyColumn segura o item que estava visível e o perfil novo ficava escondido acima do topo:
            // por 20 s, item novo entrando em cima traz a lista junto. Arrastar a lista encerra isso.
            val drag = async { list.interactionSource.interactions.first { it is DragInteraction.Start } }
            withTimeoutOrNull(20_000) {
                snapshotFlow { list.firstVisibleItemIndex }.takeWhile { !drag.isCompleted }
                    .collect { if (it != 0) list.animateScrollToItem(0) }
            }
            drag.cancel()
        }
        LaunchedEffect(active) {
            val fresh = active.filter { HomeScroll.seen.add(it) }
            if (fresh.isNotEmpty() && HomeScroll.ready) toTop()
            HomeScroll.ready = true      // a 1ª leitura (abrindo o app) só registra o que já estava rodando
        }
        // erro novo: sobe do mesmo jeito, para o aviso vermelho (que fica no topo) aparecer
        val failedIds = failed.map { it.id }
        LaunchedEffect(failedIds) {
            val fresh = failedIds.filter { HomeScroll.seenFailed.add(it) }
            if (fresh.isNotEmpty() && HomeScroll.readyFailed) toTop()
            HomeScroll.readyFailed = true
        }
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(), state = list,
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!access) item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Permissão necessária", style = MaterialTheme.typography.titleMedium)
                        Text("Para salvar os perfis, o app precisa de acesso a todos os arquivos.", style = MaterialTheme.typography.bodyMedium)
                        Button({ ctx.startActivity(allFilesAccessIntent(ctx)) }, Modifier.padding(top = 8.dp)) { Text("Conceder acesso") }
                    }
                }
            }
            items(failed, key = { it.id.toString() }) { w ->
                val url = w.tags.firstOrNull { it.startsWith("u:") }?.removePrefix("u:") ?: ""
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Falha ao baixar", style = MaterialTheme.typography.titleSmall)
                            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(w.outputData.getString("error") ?: "Erro desconhecido", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ wm.pruneWork() }) { Text("Fechar") }
                    }
                }
            }
            if (loaded != null && profiles.isEmpty() && works.none { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }) item {
                Box(Modifier.fillParentMaxWidth().padding(top = 80.dp), Alignment.Center) {
                    Text("Nenhum perfil baixado.\nToque em Adicionar para colar um link.", textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(profiles, key = { it.id }) { p ->
                val w = works.firstOrNull {
                    "u:${p.url}" in it.tags && (it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED)
                }
                ProfileCard(
                    p, w,
                    onOpen = { nav.navigate("profile/${p.id}") },
                    onCancel = { wm.cancelUniqueWork(workName(p.url)) },
                    onRecheck = { enqueueDownload(ctx, p.url) },
                    onRemove = { removing = p },
                    onClearFailures = { scope.launch { dao.clearFailures(p.id) } },
                    modifier = Modifier.animateItem()
                )
            }
            item(key = FILLER_KEY) { Spacer(Modifier.height(filler)) }
        }
    }

    removing?.let { p ->
        var files by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remover @${p.username}?") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(files, { files = it })
                    Text("Apagar também os arquivos do dispositivo")
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch(Dispatchers.IO) {
                        wm.cancelUniqueWork(workName(p.url))
                        dao.deleteProfile(p.id)
                        if (files) File(p.dir).deleteRecursively()
                    }
                    removing = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
fun ProfileCard(
    p: Profile, work: WorkInfo?, onOpen: () -> Unit, onCancel: () -> Unit,
    onRecheck: () -> Unit, onRemove: () -> Unit, onClearFailures: () -> Unit, modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf(false) }
    var showFailures by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    Card(onOpen, modifier, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.avatarPath, 56)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.handle()} · ${platformLabel(p.platform)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${p.postCount} publicações", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (work != null) IconButton(onCancel) { Icon(Icons.Default.Close, "Cancelar download") }
                else if (p.failedCount > 0) IconButton({ showFailures = true }) {
                    Icon(Icons.Default.Warning, "${p.failedCount} publicações não baixadas", tint = MaterialTheme.colorScheme.tertiary)
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Mais opções") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Sobre") }, { menu = false; about = true })
                        // reverificar = compara o perfil online com o que está no aparelho e baixa o que faltar
                        DropdownMenuItem({ Text("Reverificar perfil") }, { menu = false; onRecheck() }, enabled = work == null)
                        DropdownMenuItem({ Text("Remover perfil") }, { menu = false; onRemove() })
                    }
                }
            }
            if (work != null) WorkProgress(work, Modifier.padding(top = 10.dp))
        }
    }
    if (about) ProfileAboutSheet(p) { about = false }
    if (showFailures) FailuresDialog(p, onDismiss = { showFailures = false }, onDelete = { showFailures = false; onClearFailures() })
}

/**
 * Aviso de publicações que não foram baixadas: quantas, e cada erro agrupado (mesmo erro em vários
 * posts aparece uma vez, com a contagem). X no canto superior direito fecha; "Excluir" apaga o aviso.
 */
@Composable
private fun FailuresDialog(p: Profile, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val f = remember(p.failInfo) { p.failures() }
    val groups = remember(f) { f?.items.orEmpty().groupBy({ it.second }, { it.first }).entries.sortedByDescending { it.value.size } }
    Dialog(onDismiss) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(10.dp))
                    Text("Não baixados", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onDismiss) { Icon(Icons.Default.Close, "Fechar") }
                }
                Column(Modifier.padding(end = 16.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${p.failedCount} ${if (p.failedCount == 1) "vídeo/foto não foi baixado" else "vídeos/fotos não foram baixados"} " +
                        "de @${p.username} na última atualização.", style = MaterialTheme.typography.bodyMedium)
                    groups.forEach { (msg, ids) ->
                        Column {
                            Text("${ids.size}× $msg", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(ids.take(4).joinToString(", ") + if (ids.size > 4) " e mais ${ids.size - 4}" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    f?.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Text("Tocar em Atualizar no perfil tenta baixar de novo o que faltou.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onDelete) { Text("Excluir") }
                }
            }
        }
    }
}

// ───────────────────────── Perfil (visual por plataforma) ─────────────────────────

@Composable
fun ProfileScreen(id: Long, nav: NavController, saved: SavedStateHandle) {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val wm = remember { WorkManager.getInstance(ctx) }
    val scope = rememberCoroutineScope()
    val profile by remember(id) { ProfileData.profile(ctx, id) }.collectAsStateWithLifecycle()
    val loadedPosts by remember(id) { ProfileData.posts(ctx, id) }.collectAsStateWithLifecycle()
    val posts = loadedPosts.orEmpty()
    val p = profile
    val grid = rememberLazyGridState()
    LaunchedEffect(id) { pruneMissing(dao, id) }       // tira da tela posts cujo arquivo não existe mais
    val works by remember { HomeData.works(ctx) }.collectAsStateWithLifecycle()
    val work = p?.let { pr -> works.firstOrNull { "u:${pr.url}" in it.tags && (it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED) } }
    // Voltando do feed: a grade mostra o último vídeo visto (o feed grava a chave em "lastPost")
    val lastPost by remember(saved) { saved.getStateFlow<String?>("lastPost", null) }.collectAsStateWithLifecycle()
    LaunchedEffect(lastPost, loadedPosts != null) {
        val key = lastPost ?: return@LaunchedEffect
        val list = loadedPosts ?: return@LaunchedEffect
        val i = list.indexOfFirst { it.key == key }
        saved["lastPost"] = null
        if (i < 0) return@LaunchedEffect
        val headerItems = 1 + if (work != null) 1 else 0
        val target = i + headerItems
        val info = grid.layoutInfo
        val fullyVisible = info.visibleItemsInfo.any { v ->
            v.index == target && v.offset.y >= info.viewportStartOffset && v.offset.y + v.size.height <= info.viewportEndOffset
        }
        // fora da tela: rola até ele, deixando-o a 1/3 da altura (com contexto acima)
        if (!fullyVisible) grid.scrollToItem(target, -(info.viewportSize.height / 3).coerceAtLeast(0))
    }
    var menu by remember { mutableStateOf(false) }
    var delPost by remember { mutableStateOf<Post?>(null) }
    var clearAll by remember { mutableStateOf(false) }
    var aboutProfile by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.swipeToBack { nav.popBackStack() },
        topBar = {
            TopAppBar(
                // Instagram: usuário sem "@" e em negrito, como no app
                title = {
                    if (p?.platform == "instagram") Text(p.username, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Text(p?.handle().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                actions = {
                    if (work != null) IconButton({ p?.let { wm.cancelUniqueWork(workName(it.url)) } }) { Icon(Icons.Default.Close, "Cancelar") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Mais") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Sobre") }, { menu = false; aboutProfile = true })
                            DropdownMenuItem({ Text("Reverificar perfil") }, { menu = false; p?.let { enqueueDownload(ctx, it.url) } }, enabled = work == null)
                            DropdownMenuItem({ Text("Excluir todas as mídias") }, { menu = false; clearAll = true })
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (p == null) return@Scaffold
        // YouTube abre o player dentro do canal (com miniplayer); os outros sites abrem a tela do post
        val open = { post: Post ->
            if (p.platform == "youtube") YtSessions.open(p.id, post.key) else nav.navigate("post/" + Uri.encode(post.key))
        }
        val long = { post: Post -> delPost = post }
        when (p.platform) {
            "youtube" -> LazyColumn(Modifier.padding(pad)) {
                if (work != null) item { WorkProgress(work, Modifier.padding(16.dp, 0.dp)) }
                item { Header(p) }
                items(posts, key = { it.key }) { VideoRow(it, open, long) }
            }
            "x" -> LazyColumn(Modifier.padding(pad)) {
                if (work != null) item { WorkProgress(work, Modifier.padding(16.dp, 0.dp)) }
                item { Header(p) }
                items(posts, key = { it.key }) { TweetCard(p, it, open, long) }
            }
            else -> LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.padding(pad), state = grid,
                // Instagram separa as miniaturas por uma linha bem fina
                verticalArrangement = Arrangement.spacedBy(if (p.platform == "instagram") 1.dp else 2.dp),
                horizontalArrangement = Arrangement.spacedBy(if (p.platform == "instagram") 1.dp else 2.dp)
            ) {
                if (work != null) item(span = { GridItemSpan(maxLineSpan) }) { WorkProgress(work, Modifier.padding(16.dp, 0.dp)) }
                item(span = { GridItemSpan(maxLineSpan) }) { Header(p) }
                items(posts, key = { it.key }, contentType = { "thumb" }) { GridThumb(it, if (p.platform == "tiktok" || p.platform == "instagram") 3f / 4f else 1f, open, long,
                    instagram = p.platform == "instagram") }
            }
        }
    }
    // player do YouTube (aberto ou miniplayer) por cima da tela do canal
    if (p?.platform == "youtube") YouTubeHost(p, posts) { post -> delPost = post }
    }

    delPost?.let { post ->
        ConfirmDeleteMedia(
            onConfirm = {
                scope.launch(Dispatchers.IO) { post.deleteFiles(); dao.deletePost(post.key); dao.recount(post.profileId) }
                delPost = null
            },
            onDismiss = { delPost = null }
        )
    }
    if (aboutProfile && p != null) ProfileAboutSheet(p) { aboutProfile = false }
    if (clearAll && p != null) {
        var redo by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { clearAll = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Excluir todas as mídias?") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Permitir baixar de novo na próxima atualização", Modifier.weight(1f))
                    Switch(redo, { redo = it })
                }
            },
            confirmButton = { TextButton({ scope.launch { clearMedia(dao, p, redo) }; clearAll = false }) { Text("Excluir") } },
            dismissButton = { TextButton({ clearAll = false }) { Text("Cancelar") } }
        )
    }
}

/** Número do Instagram em português: 1.234 · 14,5 mil · 1,2 mi (",0" some, como no app). */
fun Long.igCount(): String {
    fun one(v: Double) = "%.1f".format(Locale("pt", "BR"), v).removeSuffix(",0")
    return when {
        this < 10_000 -> "%,d".format(Locale("pt", "BR"), this)
        this < 1_000_000 -> one(this / 1_000.0) + " mil"
        else -> one(this / 1_000_000.0) + " mi"
    }
}

/** Número em negrito com o rótulo embaixo, como no cabeçalho do Instagram. */
@Composable
private fun IgStat(value: Long, label: String) {
    Column {
        Text(value.igCount(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Header(p: Profile) {
    val stats = buildString {
        append("${p.postCount} publicações")
        p.followers?.let { append(" · ${it.compact()} seguidores") }
    }
    when (p.platform) {
        "tiktok" -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(p.avatarPath, 96)
            Text("@${p.username}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(stats, style = MaterialTheme.typography.bodySmall)
            if (p.bio.isNotBlank()) Text(p.bio, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
        // layout do perfil do Instagram (2025): foto à esquerda; nome e, embaixo dele, os números lado a lado; bio abaixo
        "instagram" -> Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.avatarPath, 86); Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        IgStat(p.postCount.toLong(), "publicações")
                        p.followers?.let { IgStat(it, "seguidores") }
                    }
                }
            }
            if (p.bio.isNotBlank()) Text(p.bio, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }
        else -> Column(Modifier.fillMaxWidth().padding(16.dp)) {   // YouTube e X
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.avatarPath, 80); Spacer(Modifier.width(16.dp))
                Column {
                    Text(p.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(p.handle(), style = MaterialTheme.typography.bodySmall)
                    Text(stats, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (p.bio.isNotBlank()) Text(p.bio, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
fun GridThumb(post: Post, ratio: Float, open: (Post) -> Unit, long: (Post) -> Unit, instagram: Boolean = false) {
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .combinedClickable(onLongClick = { long(post) }, onClick = { open(post) })) {
        AsyncImage(thumbOf(post), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // Instagram: só um ícone branco no canto (carrossel ou vídeo), sem contagem de fotos
        if (instagram) {
            val icon = when { post.isSlideshow -> R.drawable.ic_carousel; post.isVideo -> R.drawable.ic_reel; else -> null }
            icon?.let { Icon(painterResource(it), null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp), tint = Color.White) }
        }
        if (!instagram && post.isVideo) Icon(Icons.Default.PlayArrow, null, Modifier.align(Alignment.BottomStart).padding(4.dp), tint = Color.White)
        if (!instagram && post.isSlideshow) Text(
            "${post.slides!!.count { it == '\n' } + 1} fotos", color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = .5f)).padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
fun VideoRow(post: Post, open: (Post) -> Unit, long: (Post) -> Unit) {
    Column(Modifier.fillMaxWidth().combinedClickable(onLongClick = { long(post) }, onClick = { open(post) }).padding(bottom = 16.dp)) {
        AsyncImage(thumbOf(post), null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.DarkGray), contentScale = ContentScale.Crop)
        Text(post.title, Modifier.padding(12.dp, 8.dp, 12.dp, 0.dp), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(post.views?.let { "${it.compact()} visualizações" }, post.timestamp.date()).joinToString(" · "),
            Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TweetCard(p: Profile, post: Post, open: (Post) -> Unit, long: (Post) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { long(post) }, onClick = { open(post) }).padding(12.dp)) {
            Avatar(p.avatarPath, 44); Spacer(Modifier.width(10.dp))
            Column {
                Text("${p.displayName}  @${p.username} · ${post.timestamp.date()}", style = MaterialTheme.typography.bodySmall)
                if (post.description.isNotBlank()) Text(post.description, modifier = Modifier.padding(vertical = 4.dp))
                thumbOf(post)?.let {
                    AsyncImage(it, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                }
                Text("♥ ${post.likes?.compact() ?: 0}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        HorizontalDivider()
    }
}

// ───────────────────────── Adicionar perfil ─────────────────────────

@Composable
fun AddScreen(nav: NavController, openMenu: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }
    val target = parseProfileUrl(url)
    // já abre com o campo selecionado e o teclado na tela
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus(); keyboard?.show() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Adicionar perfil") },
            navigationIcon = { IconButton(openMenu) { Icon(Icons.Default.Menu, "Menu") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                label = { Text("Link do perfil (YouTube, TikTok, Instagram, X ou Reddit)") },
                supportingText = {
                    Text(target?.let { "Detectado: ${it.label} · @${it.username}" + if (it.seed != null) " (pelo vídeo)" else "" }
                        ?: if (url.isBlank()) "Ex.: https://www.tiktok.com/@usuario" else "Link não reconhecido")
                })
            Button({ target?.let { enqueueDownload(ctx, it.url, it.seed) }; nav.popBackStack() }, enabled = target != null) { Text("Baixar perfil") }
        }
    }
}

// ───────────────────────── Configurações ─────────────────────────

@Composable
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    // fillMaxWidth: todas as seções com a largura da tela, mesmo as de conteúdo estreito (ex.: só um botão)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    fun say(m: String) { scope.launch { snack.showSnackbar(m) } }

    var path by remember { mutableStateOf(prefs.baseDir) }
    // o campo mostra os cookies guardados (todos os sites); para adicionar é só colar embaixo
    var cookies by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { cookies = withContext(Dispatchers.IO) { CookieStore.asText(ctx) } }
    var wifi by remember { mutableStateOf(prefs.wifiOnly) }
    var parallel by remember { mutableIntStateOf(prefs.parallel) }
    var access by remember { mutableStateOf(hasStorageAccess(ctx, path)) }
    ObserveResume {
        access = hasStorageAccess(ctx, path)
        // acesso concedido depois de escolher a pasta: agora dá para verificar os perfis que já estão nela
        val p = path
        if (access && prefs.importedDir != p) scope.launch { say(useFolder(ctx, p)) }
    }
    val used by produceState(-1L, path, access) {
        val p = path
        value = if (!access) -1L
        else withContext(Dispatchers.IO) { File(p).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }
    val pickFolder = rememberFolderPicker { msg -> path = prefs.baseDir; access = hasStorageAccess(ctx, path); say(msg) }

    Scaffold(
        modifier = Modifier.swipeToBack { nav.popBackStack() },
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(title = { Text("Configurações") },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } })
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section("Aparência") {
                val labels = listOf("Sistema", "Claro", "Escuro")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    labels.forEachIndexed { i, l ->
                        SegmentedButton(ThemeState.mode == i, { ThemeState.change { ThemeState.mode = i }; prefs.themeMode = i },
                            SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(l) }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cores dinâmicas (Material You)", Modifier.weight(1f))
                    Switch(ThemeState.dynamic, { v -> ThemeState.change { ThemeState.dynamic = v }; prefs.dynamic = v })
                }
            }

            Section("Armazenamento") {
                OutlinedTextField(path, {}, Modifier.fillMaxWidth(), readOnly = true,
                    label = { Text("Pasta de downloads") })
                Button(pickFolder) { Text("Selecionar pasta") }
                if (!access) Button({
                    if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(allFilesAccessIntent(ctx))
                    else say("Conceda a permissão de armazenamento nas configurações do Android")
                }) { Text("Conceder acesso a todos os arquivos") }
                if (access) Text(if (used >= 0) "Espaço usado nessa pasta: ${formatSize(used)}" else "Calculando espaço usado…",
                    style = MaterialTheme.typography.bodySmall)
            }

            Section("Downloads") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Baixar somente no Wi‑Fi", Modifier.weight(1f))
                    Switch(wifi, { wifi = it; prefs.wifiOnly = it })
                }
                Text("Downloads simultâneos", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..MAX_PARALLEL).forEach { n -> FilterChip(parallel == n, { parallel = n; prefs.parallel = n }, { Text("$n") }) }
                }
            }

            Section(null) {
                OutlinedTextField(cookies, { cookies = it }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("Cookies (login)") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        val text = cookies
                        scope.launch(Dispatchers.IO) {
                            val msg = CookieStore.replaceAll(ctx, text)
                            cookies = CookieStore.asText(ctx)          // mostra como ficou guardado
                            say(msg)
                        }
                    }) { Text("Salvar") }
                    // Limpar apaga de verdade: antes só esvaziava o campo e os cookies continuavam salvos até apertar Salvar
                    OutlinedButton({
                        cookies = ""
                        scope.launch(Dispatchers.IO) { CookieStore.replaceAll(ctx, ""); say("Cookies removidos") }
                    }, enabled = cookies.isNotEmpty()) { Text("Limpar") }
                }
            }

            Section("Motor de download") {
                Button({
                    say("Atualizando yt-dlp…")
                    scope.launch(Dispatchers.IO) { say(runCatching { Engine.updateNow(ctx) }.getOrElse { "Erro: ${it.message}" }) }
                }) { Text("Atualizar yt-dlp") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

}

/**
 * Seletor de pasta do sistema. Ao escolher: salva, prepara a pasta e procura perfis já baixados nela
 * ([useFolder]); [onDone] recebe a mensagem para o usuário.
 */
@Composable
fun rememberFolderPicker(onDone: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDone)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val p = treeUriToPath(uri)
        if (p == null) done("Não foi possível usar essa pasta")
        else scope.launch { done(useFolder(ctx, p)) }
    }
    return { launcher.launch(null) }
}

/** "Sobre" do perfil: dados da conta, situação do último download e espaço ocupado no aparelho. */
@Composable
fun ProfileAboutSheet(p: Profile, onDismiss: () -> Unit) {
    val size by produceState(-1L, p.dir) {
        value = withContext(Dispatchers.IO) { File(p.dir).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }
    val rows = buildList {
        add("Nome" to p.displayName)
        add("Perfil" to "${p.handle()} · ${platformLabel(p.platform)}")
        add("Publicações no aparelho" to "%,d".format(p.postCount))
        p.followers?.let { add("Seguidores" to "%,d".format(it)) }
        if (p.bio.isNotBlank()) add("Bio" to p.bio)
        if (p.failedCount > 0) add("Não baixados na última atualização" to "${p.failedCount}")
        add("Espaço ocupado" to if (size >= 0) formatSize(size) else "calculando…")
        add("Link" to p.url)
        add("Pasta" to p.dir)
        // sempre o último item do "Sobre"
        if (p.updatedAt > 0) add("Informações atualizadas em" to SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(p.updatedAt)))
    }
    ModalBottomSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.avatarPath, 56); Spacer(Modifier.width(14.dp))
                Text("Sobre", style = MaterialTheme.typography.titleLarge)
            }
            rows.forEach { (k, v) ->
                Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp))
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
