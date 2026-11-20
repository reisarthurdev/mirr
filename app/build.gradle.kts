import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.profilearchive"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.profilearchive"
        minSdk = 24
        targetSdk = 34
        // versionCode = número do build do GitHub Actions + 1000 (local, sem CI: 1000)
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 1000
        versionName = "2.0"
        // Só ARM 64 bits: é o que roda em qualquer celular Android atual.
        // Cada ABI extra duplicava python + ffmpeg + quickjs dentro do APK.
        ndk { abiFilters += listOf("arm64-v8a") }
        // só os textos em português e inglês das bibliotecas (AndroidX/Material traziam ~80 idiomas)
        resourceConfigurations += listOf("pt", "en")
    }
    // Assinatura do release: keystore.properties na raiz (commitado) aponta pra keystore/mirr.jks.
    // storeFile relativo é resolvido a partir da raiz do projeto. Sem o arquivo, cai na chave de debug.
    val keyProps = rootProject.file("keystore.properties").takeIf { it.isFile }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keyProps != null) create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("password")
            keyAlias = keyProps.getProperty("alias", "mirr")
            keyPassword = keyProps.getProperty("password")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true          // R8: remove código morto e otimiza bytecode
            isShrinkResources = true        // remove recursos não usados
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs { useLegacyPackaging = true }   // necessário para o python/ffmpeg embutidos
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin") }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    // instala os baseline profiles do Compose/Media3 no APK instalado fora da Play Store (menos jank no início)
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")      // quadro do vídeo como miniatura quando não há capa
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // yt-dlp + ffmpeg para Android (0.18 traz o QuickJS que o yt-dlp atual exige)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
