# SpotiPremium

Reproductor y descargador de música offline. Busca canciones desde Spotify, las descarga desde YouTube, y las reproduce localmente sin conexión.

## Arquitectura

### Android App (Kotlin)

App nativa Android (minSdk 26, targetSdk 36) que consta de:

- **PlayerActivity** — Actividad principal. Muestra la lista de canciones de una playlist, permite reproducir, descargar, recortar y gestionar la cola.
- **SearchActivity** — Busca playlists públicas de Spotify por texto.
- **MainActivity** — Muestra playlists importadas.
- **PlayerService** — Servicio foreground con ExoPlayer. Gestiona reproducción, notificaciones media, cola de reproducción, y auto‑next al terminar una canción.
- **DownloadService** — Servicio foreground que descarga canciones. Cada canción se busca en YouTube, se obtiene un stream de audio, se descarga a un archivo temporal, se recorta (intro/outro automático), se envía al servidor PC para normalizar volumen (si está disponible), y se guarda en el almacenamiento local.
- **YouTubeClient** — Cliente para búsqueda y stream en YouTube. Soporta múltiples backends: servidor PC (yt-dlp), Piped, Invidious e InnerTube (API directa de YouTube).
- **SpotifyClient / SpotifyTokenClient** — Clientes para obtener tokens de Spotify y buscar playlists.
- **RemoteLogger** — Singleton que envía logs al servidor PC vía HTTP POST y los almacena en un buffer local.

### Servidor PC (Python + FastAPI)

Servidor auxiliar opcional que corre en `192.168.0.19:8000`:

- **`/api/youtube/search`** — Busca videos en YouTube usando yt-dlp.
- **`/api/youtube/stream`** — Resuelve la URL de stream de audio de un video.
- **`/api/youtube/download`** — Descarga el audio completo, lo normaliza con FFmpeg (loudnorm EBU R128) y lo devuelve como archivo MP3.
- **`/api/search/spotify`** — Busca playlists en Spotify usando spotapi (con TLSClient y TOTP).
- **`/api/import/spotify`** — Importa todas las canciones de una playlist de Spotify mediante la Partner API (GraphQL) con paginación manual (limit=100, offset).
- **`/api/log` y `/api/logs`** — Endpoints de logging remoto.
- **`/api/normalize`** — Normaliza un archivo de audio subido usando FFmpeg loudnorm.
- **Web UI** — Interfaz web para importar playlists y descargar canciones.

## Flujo de descarga

1. El usuario importa una playlist desde Spotify (URL pública).
2. Las canciones se guardan en la base de datos local (Room).
3. Al descargar, la app busca cada canción en YouTube:
   - **Opción A (servidor PC):** Consulta `/api/youtube/search` del servidor, los resultados se filtran con `scoreAndSort()` (requiere todas las palabras de la canción + al menos una del artista en el título, duración ≥ 120s, penalización a covers/lives/remixes).
   - **Opción B (directo):** Usa Piped, Invidious o InnerTube, también filtrados con `scoreAndSort()`.
4. Selecciona el mejor resultado, obtiene la URL del stream de audio.
5. Descarga el audio a un archivo temporal.
6. Recorta automáticamente los primeros 2 segundos y últimos 2 segundos (elimina intros hablados y silencios finales, dejando 1 segundo de margen).
7. Si el servidor PC está disponible, envía el archivo para normalización de volumen (FFmpeg loudnorm EBU R128 a -16 LUFS).
8. Guarda el archivo final en el almacenamiento (MediaStore o directorio directo).
9. Marca la canción como descargada en la base de datos.

## Flujo de reproducción

1. El usuario toca una canción descargada → `PlayerActivity.playSong()` → envía `ACTION_PLAY` a `PlayerService`.
2. `PlayerService` carga el audio con ExoPlayer y comienza la reproducción.
3. Al terminar la canción (`STATE_ENDED`), `PlayerService.playNextInPlaylist()` envía `ACTION_AUTO_NEXT` a `PlayerActivity`.
4. `PlayerActivity.playNext()` incrementa el índice en la cola actual (respeta modo shuffle/aleatorio) y reproduce la siguiente canción.
5. **Fallback:** si `PlayerActivity` no responde en 500ms, `PlayerService` reproduce la siguiente canción secuencial desde la base de datos directamente.

## Características

- **Búsqueda de playlists Spotify** por texto.
- **Importación de playlists** con paginación correcta (sin límite de 100 canciones).
- **Descarga individual o masiva** de canciones.
- **Recorte manual** de canciones (seleccionar inicio y fin en mm:ss, guarda en `Music/Recortadas`).
- **Recorte automático** de intro/outro (2 segundos al inicio y final, dejando margen de 1 segundo).
- **Normalización de volumen** (EBU R128, -16 LUFS) mediante servidor PC con FFmpeg.
- **Modos de reproducción:** secuencial, aleatorio (shuffle), por similitud.
- **Ajuste de volumen** por canción (gain almacenado en DB).
- **Cancelar descarga** desde la notificación.
- **Logging remoto** para depuración (servidor PC).

## Cómo construir

### Servidor PC
```bash
pip install -r requirements.txt
python main.py
# Abre http://localhost:8000
```

### Android App
Abrir `android/` en Android Studio y ejecutar (Mayús+F10).

## Errores conocidos y issues a solucionar

### 1. Auto-play después de skip
Cuando el usuario salta una canción manualmente (skip), el auto‑play para la siguiente canción al terminar la actual puede no funcionar si `currentQueue` está vacía o desactualizada.

**Causa:** `PlayerActivity` mantiene `currentQueue` en memoria. Si la lista de canciones cambia (nuevas descargas) o el usuario sale y vuelve, la cola puede no reflejar el estado actual.

**Fix parcial:** Fallback en `PlayerService` que reproduce directamente desde DB tras 500ms. Pero este fallback no respeta shuffle/similar mode.

### 2. Descarga de canciones incorrectas
Ocasionalmente la app descarga un video de YouTube que no corresponde a la canción solicitada.

**Causa:**
- Los resultados de `searchViaServer()` (yt-dlp) no siempre son precisos. El filtrado con `scoreAndSort()` y `selectBestMatch()` reduce falsos positivos pero no elimina todos.
- Canciones con nombres cortos (1-2 palabras de ≤2 caracteres como "XO") eluden los filtros de palabras significativas.
- Videos "Topic" a veces tienen títulos genéricos que matchean múltiples canciones.

**Fix actual:** Sistema de puntuación estricto — requiere todas las palabras de la canción y al menos una del artista en el título, duración ≥ 120s, penalizaciones fuertes (-1000) para live/cover/remix. Threshold ≥ 250 puntos.

**Pendiente:** Verificar el video descargado comparando la huella de audio (fingerprint) o usando la API de Shazam.

### 3. Normalización de volumen solo con servidor
La normalización EBU R128 depende del servidor PC con FFmpeg. Sin servidor, las canciones mantienen el volumen original de YouTube (varía entre canales).

**Fix posible:** Implementar normalización en Android mediante `MediaCodec` para decodificar a PCM, calcular RMS, y ajustar el gain almacenado en DB.

### 4. Límite de 100 canciones en importación
La API de Spotify (GraphQL) tiene un límite de 100 items por página. La app usa paginación manual con `limit=100, offset=N*100`.

**Estado:** Corregido. La función `get_tracks_from_spotify()` en `main.py` itera con offset hasta obtener todas las canciones.

### 5. Rate limiting de Spotify
La búsqueda de playlists usa TLSClient con TOTP. Si se excede el límite de requests, Spotify devuelve 429.

**Estado:** El servidor reintenta automáticamente con `auto_retries=3` en TLSClient.

### 6. Timeouts en búsqueda de YouTube
Piped, Invidious e InnerTube pueden ser lentos o no responder.

**Estado:** Timeouts configurados (8-10s). Si un backend falla, se intenta el siguiente.

### 7. Recorte manual no funciona en algunos formatos
El recorte manual usa `MediaExtractor` + `MediaMuxer` que solo soportan contenedores MP4/M4A. Archivos OGG o WebM no se pueden recortar.

**Estado:** Se muestra error "formato no compatible (solo m4a)".

### 8. Posible corrupción de base de datos
Room puede corromperse si la app se cierra durante una operación de escritura.

**Fix posible:** Backup automático de la DB al iniciar.

### 9. Notificaciones no visibles en Android 13+
Android 13+ requiere permiso `POST_NOTIFICATIONS`. La app lo solicita al iniciar pero si el usuario lo deniega, no se muestran notificaciones de descarga ni reproducción.

### 10. Consumo de batería en descargas masivas
Descargar muchas canciones seguidas mantiene la pantalla (parcialmente) y el WiFi activos.

**Fix posible:** Agrupar descargas con WorkManager y restringir a WiFi.
