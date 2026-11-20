@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.profilearchive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

private val COLLAPSED = 64.dp
private val EXPANDED = 152.dp

/**
 * Barra superior grande que encolhe com a rolagem, com UM único título que diminui e sobe até
 * a barra pequena (como o rótulo de um campo de texto ao receber foco), em vez do
 * "some com fade e reaparece" do LargeTopAppBar.
 *
 * Tudo que muda a cada frame da rolagem (altura, cor, posição e escala do título) é lido nas fases
 * de layout/desenho: a barra não recompõe enquanto rola.
 */
@Composable
fun ShrinkingTopBar(title: String, scroll: TopAppBarScrollBehavior, navigationIcon: @Composable () -> Unit) {
    val state = scroll.state
    val density = LocalDensity.current
    val collapsedPx = with(density) { COLLAPSED.toPx() }
    val expandedPx = with(density) { EXPANDED.toPx() }
    SideEffect { state.heightOffsetLimit = collapsedPx - expandedPx }   // quanto a barra pode encolher

    val colors = MaterialTheme.colorScheme
    val big = MaterialTheme.typography.headlineMedium
    val small = MaterialTheme.typography.titleLarge
    val endScale = small.fontSize.value / big.fontSize.value
    var textH by remember { mutableIntStateOf(0) }

    Box(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(lerp(colors.surface, colors.surfaceContainer, state.collapsedFraction)) }
            .statusBarsPadding()
            .layout { m, c ->
                val h = (expandedPx + state.heightOffset).roundToInt().coerceIn(collapsedPx.roundToInt(), expandedPx.roundToInt())
                val p = m.measure(c.copy(minHeight = h, maxHeight = h))
                layout(p.width, h) { p.place(0, 0) }
            }
            .clipToBounds()
    ) {
        Box(Modifier.padding(start = 4.dp).height(COLLAPSED), contentAlignment = Alignment.CenterStart) { navigationIcon() }
        Text(
            title, style = big, color = colors.onSurface, maxLines = 1,
            modifier = Modifier.onSizeChanged { textH = it.height }.graphicsLayer {
                val f = state.collapsedFraction                       // 0 = aberta … 1 = recolhida
                val s = lerp(1f, endScale, f)
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = s; scaleY = s
                val barH = expandedPx + state.heightOffset
                val openY = barH - 28.dp.toPx() - textH               // perto da base da barra grande
                val closedY = (collapsedPx - textH * s) / 2f          // centralizado na barra pequena
                translationX = lerp(16.dp.toPx(), 56.dp.toPx(), f)    // 56dp = depois do ícone do menu
                translationY = lerp(openY, closedY, f)
            }
        )
    }
}

/**
 * TopAppBar do Material que troca de cor junto com o resto do app. O original anima a cor do fundo
 * sozinho (mola de ~0,5 s) e, ao trocar o tema, terminava depois do fade da troca. Recriado a cada
 * esquema de cores, ele já nasce com a cor nova.
 */
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    val c = MaterialTheme.colorScheme
    androidx.compose.runtime.key(c.surface, c.surfaceContainer, c.onSurface) {
        androidx.compose.material3.TopAppBar(title = title, navigationIcon = navigationIcon, actions = actions)
    }
}
