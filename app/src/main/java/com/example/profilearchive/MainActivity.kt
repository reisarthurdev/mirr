package com.example.profilearchive

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder

object ThemeState {
    var mode by mutableIntStateOf(0)       // 0 sistema, 1 claro, 2 escuro
    var dynamic by mutableStateOf(true)

    /** Foto da tela tirada logo antes de trocar o tema: fica por cima e some em fade. */
    var snapshot by mutableStateOf<android.graphics.Bitmap?>(null)
    internal var window: java.lang.ref.WeakReference<android.view.Window>? = null

    /**
     * Troca de tema sem cada componente mudar de cor no seu próprio ritmo (botões, switches e cards
     * animam a cor sozinhos, com durações diferentes): a tela atual vira uma imagem por cima, o tema
     * troca inteiro por baixo e a imagem some em fade. Tudo muda junto.
     * A foto é do que está na tela (PixelCopy); sem ela, troca direto.
     */
    fun change(apply: () -> Unit) {
        val w = window?.get()
        val v = w?.decorView
        if (w == null || v == null || v.width <= 0 || v.height <= 0 || android.os.Build.VERSION.SDK_INT < 26) { apply(); return }
        val bmp = runCatching { android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: run { apply(); return }
        runCatching {
            android.view.PixelCopy.request(w, bmp, { result ->
                if (result == android.view.PixelCopy.SUCCESS) snapshot = bmp else bmp.recycle()
                apply()
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { bmp.recycle(); apply() }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        prefs.migrateLegacyDir()
        // miniatura de vídeo sem capa: o Coil extrai um quadro do próprio arquivo
        Coil.setImageLoader(ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build())
        ThemeState.mode = prefs.themeMode
        ThemeState.dynamic = prefs.dynamic

        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty())
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(perms.toTypedArray())

        ThemeState.window = java.lang.ref.WeakReference(window)
        setContent {
            val ctx = LocalContext.current
            val dark = when (ThemeState.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            val scheme = remember(dark, ThemeState.dynamic) { appColorScheme(ctx, dark, ThemeState.dynamic) }
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            MaterialTheme(colorScheme = scheme) {
                Box(Modifier.fillMaxSize()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot() }
                    // foto do tema anterior por cima, sumindo em fade (ver ThemeState.change)
                    ThemeState.snapshot?.let { shot ->
                        val alpha = remember(shot) { Animatable(1f) }
                        LaunchedEffect(shot) {
                            withFrameNanos { }                      // o tema novo já está desenhado por baixo
                            alpha.animateTo(0f, tween(300, easing = LinearOutSlowInEasing))
                            if (ThemeState.snapshot === shot) ThemeState.snapshot = null   // o GC libera o bitmap
                        }
                        Image(shot.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }
                            .pointerInput(Unit) {}, contentScale = ContentScale.FillBounds)
                    }
                }
            }
        }
    }
}
