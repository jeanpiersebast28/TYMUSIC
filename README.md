# TYMusic lite

**TYMusic lite** es un cliente ligero de YouTube Music para Android con reproducción en segundo plano real, bloqueo de anuncios e inicio de sesión con Google, todo dentro de una interfaz nativa y sencilla.

## Características

- **Reproducción en segundo plano** — Los videos siguen sonando al minimizar la app o apagar la pantalla.
- **Inicia sesión con tu cuenta de Google** — Flujo OAuth completo integrado en la app.
- **Controles multimedia nativos** — Notificación con play/pausa, siguiente/anterior, barra de progreso ajustable, título, artista y portada sincronizados (MediaSession).
- **Bloqueo de anuncios** — Filtrado de URLs publicitarias y salto automático de anuncios.
- **Botón "atrás" inteligente** — Al retroceder desde una canción vuelve a tu página anterior real (búsqueda, inicio, etc.).
- **Interfaz limpia** — Sin botones promocionales ("Abrir app"), sin distracciones.

## Requisitos

- Android 7.0 (API 24) o superior
- Permisos: notificaciones y reproducción multimedia
- **Conexión a internet**: obligatoria. Esta app reproduce en streaming y **no permite descargar ni guardar canciones** para escuchar sin conexión.

## Instalación

Descarga el APK más reciente desde la sección [Releases](https://github.com/jeanpiersebast28/TYMusicLite/releases), ábrelo en tu celular y acepta la instalación si el sistema lo solicita.

## Compilar desde código fuente

```bash
git clone https://github.com/jeanpiersebast28/TYMusicLite.git
cd TYMusicLite
./gradlew assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`.

## Tecnología

Kotlin · Jetpack Compose · WebView · MediaSession · Servicio en primer plano
