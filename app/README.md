# mirr (Android)

Kotlin + Jetpack Compose (Material 3) + Room + WorkManager + yt-dlp (youtubedl-android 0.18) + Media3.

## Compilar
Abra a pasta no Android Studio (JDK 17) e faça o Gradle sync, ou:
  gradle wrapper --gradle-version 8.9 && ./gradlew assembleRelease
APK: app/build/outputs/apk/release/app-release.apk
Use o release: com R8 e baseline profiles ele roda bem mais liso que o debug (Compose em debug é lento por natureza).
O release é assinado com keystore/mirr.jks (senha em keystore.properties, ambos no repo).
CI: .github/workflows/release.yml gera o APK a cada push na main e publica em Releases (versionCode = nº do build + 1000).
Só arm64-v8a (qualquer celular atual). Celular 32 bits antigo: adicione "armeabi-v7a" em abiFilters.

## Pastas
Padrão: /storage/emulated/0/Android/Documents/Users/perfis
  <pasta>/<Aplicativo>/<usuário>/pfp.jpg
  <pasta>/<Aplicativo>/<usuário>/<id>.mp4 | <id>.mkv | <id>.jpg
  <pasta>/<Aplicativo>/<usuário>/<id>.mp3 + <id>.slide01.jpg, <id>.slide02.jpg…   (post de fotos do TikTok)
  <pasta>/<Aplicativo>/<usuário>/.dados/  (info.json, miniaturas, archive.txt, secuid.txt, <id>.slides)
Requer "acesso a todos os arquivos" (o app avisa na tela inicial).

## Plugin do yt-dlp
app/src/main/assets/ytdlp/pa_tiktok.py é instalado em ~/.yt-dlp/plugins do python embutido.
Ele adiciona as URLs das fotos dos posts de slides ao .info.json e uma forma extra de achar o
secUid de perfis do TikTok cuja página vem bloqueada.
