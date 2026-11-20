package com.example.profilearchive

import android.content.Context
import android.os.Build
import androidx.annotation.ColorRes
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Material You montado direto das paletas tonais do sistema (system_accent1/2/3, neutral1/2).
 *
 * Por quê: no Android 14+ o dynamicDarkColorScheme() do Compose lê os tokens novos
 * (system_primary_dark, system_surface_container…). Em aparelhos Samsung esses tokens nem sempre
 * acompanham a paleta do papel de parede, e o app saía cinza. As paletas tonais existem desde o
 * Android 12 e são exatamente o que a One UI gera a partir do papel de parede.
 * O mapeamento de tons segue a especificação do Material 3 (ex.: primary = tom 80 no escuro, 40 no claro).
 */
@RequiresApi(31)
fun wallpaperColorScheme(ctx: Context, dark: Boolean): ColorScheme {
    fun c(@ColorRes id: Int) = ContextCompat.getColor(ctx, id)
    fun col(@ColorRes id: Int) = Color(c(id))
    /** Tom arbitrário (L* do CIELAB) a partir de um tom da paleta: usado nas superfícies (6, 12, 17…). */
    fun tone(@ColorRes base: Int, t: Double): Color {
        val lab = DoubleArray(3); ColorUtils.colorToLAB(c(base), lab)
        return Color(ColorUtils.LABToColor(t, lab[1], lab[2]))
    }
    return if (dark) darkColorScheme(
        primary = col(android.R.color.system_accent1_200), onPrimary = col(android.R.color.system_accent1_800),
        primaryContainer = col(android.R.color.system_accent1_700), onPrimaryContainer = col(android.R.color.system_accent1_100),
        inversePrimary = col(android.R.color.system_accent1_600),
        secondary = col(android.R.color.system_accent2_200), onSecondary = col(android.R.color.system_accent2_800),
        secondaryContainer = col(android.R.color.system_accent2_700), onSecondaryContainer = col(android.R.color.system_accent2_100),
        tertiary = col(android.R.color.system_accent3_200), onTertiary = col(android.R.color.system_accent3_800),
        tertiaryContainer = col(android.R.color.system_accent3_700), onTertiaryContainer = col(android.R.color.system_accent3_100),
        background = tone(android.R.color.system_neutral1_900, 6.0), onBackground = col(android.R.color.system_neutral1_100),
        surface = tone(android.R.color.system_neutral1_900, 6.0), onSurface = col(android.R.color.system_neutral1_100),
        surfaceVariant = col(android.R.color.system_neutral2_700), onSurfaceVariant = col(android.R.color.system_neutral2_200),
        surfaceTint = col(android.R.color.system_accent1_200),
        inverseSurface = col(android.R.color.system_neutral1_100), inverseOnSurface = col(android.R.color.system_neutral1_800),
        outline = col(android.R.color.system_neutral2_400), outlineVariant = col(android.R.color.system_neutral2_700),
        surfaceDim = tone(android.R.color.system_neutral1_900, 6.0),
        surfaceBright = tone(android.R.color.system_neutral1_900, 24.0),
        surfaceContainerLowest = tone(android.R.color.system_neutral1_900, 4.0),
        surfaceContainerLow = col(android.R.color.system_neutral1_900),
        surfaceContainer = tone(android.R.color.system_neutral1_900, 12.0),
        surfaceContainerHigh = tone(android.R.color.system_neutral1_800, 17.0),
        surfaceContainerHighest = tone(android.R.color.system_neutral1_800, 22.0)
    ) else lightColorScheme(
        primary = col(android.R.color.system_accent1_600), onPrimary = col(android.R.color.system_accent1_0),
        primaryContainer = col(android.R.color.system_accent1_100), onPrimaryContainer = col(android.R.color.system_accent1_900),
        inversePrimary = col(android.R.color.system_accent1_200),
        secondary = col(android.R.color.system_accent2_600), onSecondary = col(android.R.color.system_accent2_0),
        secondaryContainer = col(android.R.color.system_accent2_100), onSecondaryContainer = col(android.R.color.system_accent2_900),
        tertiary = col(android.R.color.system_accent3_600), onTertiary = col(android.R.color.system_accent3_0),
        tertiaryContainer = col(android.R.color.system_accent3_100), onTertiaryContainer = col(android.R.color.system_accent3_900),
        background = tone(android.R.color.system_neutral1_10, 98.0), onBackground = col(android.R.color.system_neutral1_900),
        surface = tone(android.R.color.system_neutral1_10, 98.0), onSurface = col(android.R.color.system_neutral1_900),
        surfaceVariant = col(android.R.color.system_neutral2_100), onSurfaceVariant = col(android.R.color.system_neutral2_700),
        surfaceTint = col(android.R.color.system_accent1_600),
        inverseSurface = col(android.R.color.system_neutral1_800), inverseOnSurface = col(android.R.color.system_neutral1_50),
        outline = col(android.R.color.system_neutral2_500), outlineVariant = col(android.R.color.system_neutral2_200),
        surfaceDim = tone(android.R.color.system_neutral1_100, 87.0),
        surfaceBright = tone(android.R.color.system_neutral1_10, 98.0),
        surfaceContainerLowest = col(android.R.color.system_neutral1_0),
        surfaceContainerLow = tone(android.R.color.system_neutral1_50, 96.0),
        surfaceContainer = tone(android.R.color.system_neutral1_50, 94.0),
        surfaceContainerHigh = tone(android.R.color.system_neutral1_100, 92.0),
        surfaceContainerHighest = col(android.R.color.system_neutral1_100)
    )
}

fun appColorScheme(ctx: Context, dark: Boolean, useWallpaper: Boolean): ColorScheme = when {
    useWallpaper && Build.VERSION.SDK_INT >= 31 -> wallpaperColorScheme(ctx, dark)
    dark -> darkColorScheme()
    else -> lightColorScheme()
}
