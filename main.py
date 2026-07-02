import asyncio
import datetime
import logging
import os
import re
import shutil
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import uvicorn
from fastapi import FastAPI, File, Form, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, FileResponse, JSONResponse

# Ensure ffmpeg is in PATH for yt-dlp
_FFMPEG_DIR = r"C:\Users\maite\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1.2-full_build\bin"
if os.path.isdir(_FFMPEG_DIR):
    os.environ.setdefault("PATH", "")
    os.environ["PATH"] = _FFMPEG_DIR + os.pathsep + os.environ["PATH"]

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("spotipremium")

app = FastAPI(title="SpotiPremium Downloader")
executor = ThreadPoolExecutor(max_workers=2)

# Remote log buffer from the Android app
_remote_logs: list[dict] = []
_MAX_REMOTE_LOGS = 5000

BASE_DIR = Path(__file__).parent
DOWNLOADS_DIR = BASE_DIR / "downloads"
DOWNLOADS_DIR.mkdir(exist_ok=True)
TEMPLATES_DIR = BASE_DIR / "templates"
LIBRARY_PATH = DOWNLOADS_DIR / "library.json"

tasks: dict[str, dict] = {}

# Remove legacy task-id folders and stale library on startup
LIBRARY_PATH.unlink(missing_ok=True)
for _p in list(DOWNLOADS_DIR.iterdir()):
    if _p.is_dir() and len(_p.name) == 12 and _p.name.isalnum():
        shutil.rmtree(_p, ignore_errors=True)
    elif _p.suffix == ".zip" and _p.stem.startswith("spotipremium_"):
        _p.unlink(missing_ok=True)


def _load_library() -> dict[str, str]:
    import json
    if LIBRARY_PATH.exists():
        return json.loads(LIBRARY_PATH.read_text(encoding="utf-8"))
    # Scan existing downloads for already-downloaded mp3s
    lib: dict[str, str] = {}
    for fpath in DOWNLOADS_DIR.rglob("*.mp3"):
        parts = fpath.stem.split(" - ", 2)
        if len(parts) == 3:
            _, artist, name = parts  # old: "001 - Artist - Name"
        elif len(parts) == 2:
            artist, name = parts     # new: "Artist - Name"
        else:
            continue
        key = _library_key(artist, name)
        if key not in lib:
            lib[key] = str(fpath.resolve())
    if lib:
        _save_library(lib)
    return lib


def _save_library(lib: dict[str, str]) -> None:
    import json
    LIBRARY_PATH.write_text(json.dumps(lib, ensure_ascii=False, indent=2), encoding="utf-8")


def _library_key(artist: str, name: str) -> str:
    return f"{artist.strip().lower()} - {name.strip().lower()}"


def _sanitize_name(name: str) -> str:
    name = re.sub(r'[\\/*?:"<>|]', "", name).strip()
    name = re.sub(r"\s+", " ", name)
    name = name.strip(". ")
    return name[:120] or "Unknown"


def html_page() -> str:
    path = TEMPLATES_DIR / "index.html"
    return path.read_text(encoding="utf-8")


def extract_playlist_id(url: str) -> str:
    m = re.search(r"playlist/([a-zA-Z0-9]+)", url)
    if m:
        return m.group(1)
    raise ValueError("URL de playlist no válida. Debe contener 'playlist/...'")


def get_tracks_from_spotify(playlist_id: str) -> tuple[str, list[dict]]:
    """Fetch a Spotify playlist's tracks via Partner API (GraphQL) with correct pagination.
    Uses spotapi's PublicPlaylist.get_playlist_info() with limit=100 and manual offset,
    avoiding the UPPER_LIMIT=343 bug in paginate_playlist()."""
    tracks: list[dict] = []
    playlist_name = "Playlist"

    try:
        from spotapi.public import PublicPlaylist

        pl = PublicPlaylist(f"https://open.spotify.com/playlist/{playlist_id}")

        # First page: get playlist name + first batch of tracks
        info = pl.get_playlist_info(limit=100)
        content = info.get("data", {}).get("playlistV2", {}).get("content", {})
        playlist_name = info.get("data", {}).get("playlistV2", {}).get("name",
                        info.get("data", {}).get("playlistV2", {}).get("title", "Playlist"))
        total_count = content.get("totalCount", 0)

        def extract_tracks(chunk_content):
            items = chunk_content.get("items", [])
            for item in items:
                item_data = item.get("itemV2", {}).get("data", {})
                typename = item_data.get("__typename", "")
                if typename == "Episode":
                    continue
                track = item_data.get("track") or item_data
                name = track.get("name", "")
                if not name:
                    continue
                artists_obj = track.get("artists", {})
                artist_names = []
                for a in artists_obj.get("items", []):
                    profile = a.get("profile", {})
                    aname = profile.get("name", "")
                    if aname:
                        artist_names.append(aname)
                    elif isinstance(a, str):
                        artist_names.append(a)
                artist = ", ".join(artist_names) if artist_names else "Unknown"
                tracks.append({"name": name, "artist": artist})

        # Extract from first page
        extract_tracks(content)

        # Paginate with limit=100 (not 343, because API caps at 100)
        limit = 100
        offset = limit
        while offset < total_count:
            page = pl.get_playlist_info(limit=limit, offset=offset)
            page_content = page.get("data", {}).get("playlistV2", {}).get("content", {})
            page_items = page_content.get("items", [])
            if not page_items:
                break
            extract_tracks(page_content)
            offset += limit
    except Exception as e:
        log.warning("Spotify import failed: %s", e)

    return playlist_name, tracks


def _is_topic(entry: dict) -> bool:
    channel = (entry.get("channel") or "").lower()
    uploader = (entry.get("uploader") or "").lower()
    return " - topic" in channel or uploader.endswith("topic")


def _find_urls(artist: str, name: str) -> tuple[list[str], bool]:
    """Returns (urls, is_topic). Prefers Topic channels (clean audio only)."""
    import yt_dlp
    opts = {
        "quiet": True, "no_warnings": True,
        "extract_flat": True, "source_address": "0.0.0.0",
        "http_headers": {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"},
    }
    queries = (f"ytsearch10:{artist} - {name}", f"ytsearch10:{name} {artist}")
    seen: set[str] = set()
    topic_urls: list[str] = []
    fallback_urls: list[str] = []

    for q in queries:
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(q, download=False)
                for entry in (info.get("entries") or []):
                    url = entry.get("url") or entry.get("webpage_url") or ""
                    if not url or url in seen:
                        continue
                    seen.add(url)
                    title = (entry.get("title") or "").lower()
                    bad = ("live", "cover", "karaoke", "remix", "instrumental",
                           "acoustic", "interview", "podcast", "talk", "behind",
                           "reaction", "tutorial", "lesson", "review", "commentary")
                    if any(b in title for b in bad):
                        continue
                    if _is_topic(entry):
                        topic_urls.append(url)
                    else:
                        fallback_urls.append(url)
        except Exception:
            continue

    if topic_urls:
        return topic_urls, True
    return fallback_urls, False


def download_song_yt(artist: str, name: str, output_path: str) -> Path | None:
    import yt_dlp

    urls, is_topic = _find_urls(artist, name)
    if not urls:
        log.warning("No se encontró resultado para '%s %s'", artist, name)
        return None

    dl_opts = {
        "format": "bestaudio[abr<=128]/bestaudio/best",
        "outtmpl": output_path,
        "postprocessors": [
            {
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "128",
            }
        ],
        "quiet": True,
        "no_warnings": True,
        "source_address": "0.0.0.0",
        "http_headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        },
        "retries": 3,
        "fragment_retries": 3,
    }

    # For non-Topic channels, use SponsorBlock to cut intro/outro/preview
    if not is_topic:
        dl_opts.setdefault("postprocessors", []).insert(0, {
            "key": "SponsorBlock",
            "categories": ["intro", "outro", "preview", "interaction"],
        })
        dl_opts["sponsorblock_remove"] = ["intro", "outro", "preview", "interaction"]

    for url in urls:
        try:
            with yt_dlp.YoutubeDL(dl_opts) as ydl:
                ydl.download([url])
                parent = Path(output_path).parent
                files = list(parent.glob("*.mp3"))
                if files:
                    result_path = max(files, key=lambda f: f.stat().st_mtime)
                    # Normalize audio
                    norm_path = result_path.with_stem(result_path.stem + "_norm")
                    if _normalize_audio(str(result_path), str(norm_path)):
                        if norm_path.exists():
                            result_path.unlink(missing_ok=True)
                            norm_path.rename(result_path)
                    return result_path
        except Exception as e:
            log.warning("yt-dlp falló descarga de %s para '%s %s': %s", url, artist, name, e)
    return None


def _normalize_audio(input_path: str, output_path: str) -> bool:
    """Normalize audio using FFmpeg EBU R128 loudnorm. Returns True on success."""
    try:
        import subprocess
        cmd = [
            "ffmpeg", "-y", "-i", input_path,
            "-af", "loudnorm=I=-16:LRA=11:TP=-1.5",
            "-ar", "44100",
            output_path,
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        if result.returncode != 0:
            log.warning("Normalize failed for %s: %s", input_path, result.stderr[:200])
            Path(output_path).unlink(missing_ok=True)
            return False
        return True
    except Exception as e:
        log.warning("Normalize exception for %s: %s", input_path, e)
        Path(output_path).unlink(missing_ok=True)
        return False


@app.get("/", response_class=HTMLResponse)
async def home():
    return html_page()


# --- Remote Logging ---

@app.post("/api/log")
async def api_log(entry: str = Form(...)):
    _remote_logs.append({"time": datetime.datetime.now().isoformat(), "entry": entry})
    while len(_remote_logs) > _MAX_REMOTE_LOGS:
        _remote_logs.pop(0)
    return {"ok": True}

@app.get("/api/logs")
async def api_logs(limit: int = 200):
    return {"logs": _remote_logs[-limit:]}

@app.get("/api/logs/clear")
async def api_logs_clear():
    _remote_logs.clear()
    return {"ok": True}

@app.get("/api/youtube/download")
async def api_youtube_download(video_id: str = ""):
    """Download a YouTube video's audio, normalize with FFmpeg, return the file."""
    if not video_id.strip():
        return JSONResponse({"error": "no video_id"}, status_code=400)
    import tempfile
    import yt_dlp
    tmp_out = tempfile.NamedTemporaryFile(suffix=".mp3", delete=False)
    tmp_out.close()
    try:
        dl_opts = {
            "format": "bestaudio[abr<=128]/bestaudio/best",
            "outtmpl": tmp_out.name.replace(".mp3", ".%(ext)s"),
            "postprocessors": [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "128",
            }],
            "quiet": True, "no_warnings": True, "source_address": "0.0.0.0",
            "http_headers": {"User-Agent": "Mozilla/5.0"},
            "retries": 2, "fragment_retries": 2,
        }
        url = f"https://www.youtube.com/watch?v={video_id}"
        await asyncio.get_event_loop().run_in_executor(executor, lambda: yt_dlp.YoutubeDL(dl_opts).download([url]))

        mp3 = Path(tmp_out.name)
        if not mp3.exists():
            # Try with .mp3 extension (yt-dlp might have added it)
            mp3 = Path(tmp_out.name.replace(".mp3", "") + ".mp3")
        if not mp3.exists():
            return JSONResponse({"error": "download failed"}, status_code=500)

        # Normalize
        norm_path = mp3.with_stem(mp3.stem + "_norm")
        ok = await asyncio.get_event_loop().run_in_executor(
            executor, _normalize_audio, str(mp3), str(norm_path)
        )
        if ok and norm_path.exists():
            mp3.unlink(missing_ok=True)
            return FileResponse(str(norm_path), media_type="audio/mpeg",
                               filename=f"{video_id}.mp3",
                               headers={"Content-Disposition": f'attachment; filename="{video_id}.mp3"'})
        return FileResponse(str(mp3), media_type="audio/mpeg",
                           filename=f"{video_id}.mp3")
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)

@app.post("/api/normalize")
async def api_normalize(file: UploadFile = File(...)):
    """Normalize an uploaded audio file using FFmpeg EBU R128 loudnorm."""
    import tempfile
    try:
        suffix = Path(file.filename or "audio.m4a").suffix
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp_in:
            tmp_in.write(await file.read())
            tmp_in_path = tmp_in.name
        tmp_out_path = tmp_in_path + "_norm" + suffix
        ok = await asyncio.get_event_loop().run_in_executor(
            executor, _normalize_audio, tmp_in_path, tmp_out_path
        )
        if ok and Path(tmp_out_path).exists():
            return FileResponse(tmp_out_path, media_type="audio/mp4", filename=f"normalized{suffix}")
        return JSONResponse({"error": "normalization failed"}, status_code=500)
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)
    finally:
        Path(tmp_in_path).unlink(missing_ok=True)

# --- Player API ---

@app.get("/api/playlists")
async def api_playlists():
    items = []
    for p in DOWNLOADS_DIR.iterdir():
        if p.is_dir():
            mp3s = [f for f in p.iterdir() if f.suffix == ".mp3"]
            if mp3s:
                items.append({
                    "name": p.name,
                    "song_count": len(mp3s),
                    "path": p.name,
                })
    return items


@app.get("/api/playlists/{folder:path}")
async def api_playlist(folder: str):
    folder_path = DOWNLOADS_DIR / folder
    if not folder_path.is_dir():
        return {"error": "Playlist no encontrada"}
    songs = []
    for f in sorted(folder_path.iterdir()):
        if f.suffix == ".mp3":
            parts = f.stem.split(" - ", 1)
            artist = parts[0] if len(parts) > 1 else "Unknown"
            title = parts[1] if len(parts) > 1 else f.stem
            songs.append({
                "artist": artist,
                "title": title,
                "file": f.name,
                "size": f.stat().st_size,
            })
    return {"name": folder, "songs": songs}


@app.get("/api/stream/{folder}/{song:path}")
async def api_stream(folder: str, song: str):
    file_path = DOWNLOADS_DIR / folder / song
    if not file_path.is_file():
        return HTMLResponse("No encontrado", status_code=404)
    return FileResponse(
        str(file_path),
        media_type="audio/mpeg",
        filename=song,
        headers={"Accept-Ranges": "bytes"},
    )


@app.get("/api/similar/{folder}")
async def api_similar(folder: str, artist: str = "", exclude: str = ""):
    folder_path = DOWNLOADS_DIR / folder
    if not folder_path.is_dir():
        return {"error": "Playlist no encontrada"}
    excluded = set(exclude.split(",")) if exclude else set()
    candidates = []
    for f in folder_path.iterdir():
        if f.suffix == ".mp3" and f.name not in excluded:
            parts = f.stem.split(" - ", 1)
            a = parts[0] if len(parts) > 1 else ""
            candidates.append((a, f.name))
    if not candidates:
        return {"song": None}
    # Same artist first
    same_artist = [c for c in candidates if c[0].lower() == artist.lower()]
    import random
    if same_artist:
        chosen = random.choice(same_artist)
    else:
        chosen = random.choice(candidates)
    parts = Path(chosen[1]).stem
    parts_list = parts.split(" - ", 1)
    return {
        "song": {
            "file": chosen[1],
            "artist": parts_list[0] if len(parts_list) > 1 else "",
            "title": parts_list[1] if len(parts_list) > 1 else parts,
        }
    }


@app.get("/api/youtube/search")
async def api_youtube_search(q: str = ""):
    """Search YouTube using yt-dlp and return video results."""
    if not q.strip():
        return {"results": []}
    try:
        import yt_dlp
        opts = {
            "quiet": True, "no_warnings": True,
            "extract_flat": True, "source_address": "0.0.0.0",
            "http_headers": {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"},
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(f"ytsearch10:{q}", download=False)
            entries = info.get("entries") or []
            results = []
            for entry in entries:
                url = entry.get("url") or entry.get("webpage_url") or ""
                vid = ""
                if "v=" in url:
                    vid = url.split("v=")[-1].split("&")[0]
                elif "/shorts/" in url:
                    vid = url.split("/shorts/")[-1].split("?")[0]
                results.append({
                    "id": vid,
                    "title": entry.get("title", ""),
                    "uploader": entry.get("channel", "") or entry.get("uploader", ""),
                    "url": url,
                    "duration": entry.get("duration") or 0,
                })
            return {"results": results}
    except Exception as e:
        return {"error": str(e), "results": []}


@app.get("/api/youtube/stream")
async def api_youtube_stream(video_id: str = ""):
    """Get best audio stream URL for a YouTube video using yt-dlp."""
    if not video_id.strip():
        return {"error": "no video_id"}
    try:
        import yt_dlp
        opts = {
            "quiet": True, "no_warnings": True,
            "source_address": "0.0.0.0",
            "http_headers": {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"},
        }
        url = f"https://www.youtube.com/watch?v={video_id}"
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
            formats = info.get("formats") or []
            audio = [f for f in formats if f.get("vcodec") == "none" and f.get("acodec") != "none"]
            audio.sort(key=lambda f: f.get("abr", 0) or 0, reverse=True)
            if audio:
                best = audio[0]
                stream_url = best.get("url") or ""
                return {
                    "url": stream_url,
                    "mimeType": best.get("ext", "m4a"),
                    "quality": f'{best.get("abr", 0)} kbps',
                }
            return {"error": "no audio found"}
    except Exception as e:
        return {"error": str(e)}


@app.get("/api/search/spotify")
async def api_search_spotify(q: str = ""):
    """Search Spotify for playlists (uses PC's spotapi)."""
    if not q.strip():
        return {"results": []}
    try:
        # Use spotapi's TLSClient for proper browser impersonation
        from spotapi.http.request import TLSClient
        import json, re, base64, urllib.parse
        client = TLSClient("chrome120", "", auto_retries=3)
        try:
            # Step 1: Get initial session
            r = client.get("https://open.spotify.com")
            if r.status_code != 200:
                return {"results": [], "error": "cannot reach spotify"}

            # Extract app server config for client version
            match = re.search(r'<script id="appServerConfig" type="text/plain">([^<]+)</script>', r.raw.text)
            if not match:
                return {"results": [], "error": "no app config found"}
            cfg = json.loads(base64.b64decode(match.group(1)).decode("utf-8"))
            client_version = cfg.get("clientVersion", "1.2.0")
            device_id = client.cookies.get("sp_t", "")

            # Step 2: Get access token via TOTP endpoint
            from spotapi.client import generate_totp
            totp, version = generate_totp()
            token_resp = client.get("https://open.spotify.com/api/token", params={
                "reason": "init",
                "productType": "web-player",
                "totp": totp,
                "totpVer": version,
                "totpServer": totp,
            })
            if token_resp.status_code != 200:
                return {"results": [], "error": f"token endpoint returned {token_resp.status_code}"}
            access_token = token_resp.response.get("accessToken", "")
            if not access_token:
                return {"results": [], "error": "no access token"}

            # Step 3: Search using Web API
            headers = {
                "Authorization": f"Bearer {access_token}",
                "Accept": "application/json",
            }
            search_url = f"https://api.spotify.com/v1/search?q={urllib.parse.quote(q)}&type=playlist&limit=20"
            r = client.get(search_url, headers=headers)
            if r.status_code == 429:
                return {"results": [], "error": "rate limited"}
            if r.status_code != 200:
                return {"results": [], "error": f"search API returned {r.status_code}"}
            data = r.response
            playlists = data.get("playlists", {}).get("items", [])
            results = []
            for pl in playlists:
                images = pl.get("images", [])
                img = images[0]["url"] if images else ""
                results.append({
                    "id": pl["id"],
                    "name": pl["name"],
                    "description": pl.get("description", ""),
                    "imageUrl": img,
                    "songCount": pl.get("tracks", {}).get("total", 0),
                })
            return {"results": results}
        finally:
            client.close()
    except Exception as e:
        import traceback
        return {"results": [], "error": str(e)}


@app.get("/api/import/spotify")
async def api_import_spotify(url: str = ""):
    """Import a Spotify playlist by URL and return track data."""
    if not url.strip():
        return {"error": "no URL"}
    try:
        playlist_id = extract_playlist_id(url)
    except ValueError as e:
        return {"error": str(e)}
    try:
        name, tracks = await asyncio.get_event_loop().run_in_executor(
            executor, get_tracks_from_spotify, playlist_id)
        return {"id": playlist_id, "name": name, "tracks": tracks}
    except Exception as e:
        return {"error": str(e)}


@app.post("/start")
async def start_download(url: str = Form(...)):
    task_id = uuid.uuid4().hex[:12]
    try:
        playlist_id = extract_playlist_id(url)
    except ValueError as e:
        return {"error": str(e)}

    tasks[task_id] = {
        "status": "queued",
        "progress": 0,
        "total": 0,
        "current": None,
        "songs": [],
        "error": None,
        "zip_path": None,
        "output_dir": None,
        "playlist_id": playlist_id,
    }

    asyncio.create_task(_run_download(task_id, url, playlist_id))
    return {"task_id": task_id}


@app.websocket("/ws/{task_id}")
async def ws_progress(websocket: WebSocket, task_id: str):
    await websocket.accept()
    last_status = None
    try:
        while True:
            task = tasks.get(task_id)
            if not task:
                await websocket.send_json({"status": "not_found"})
                break

            current = {
                "status": task["status"],
                "progress": task["progress"],
                "total": task["total"],
                "current": task["current"],
                "songs": task["songs"],
                "error": task["error"],
                "zip_path": task.get("zip_path"),
            }

            if current != last_status:
                await websocket.send_json(current)
                last_status = current

            if task["status"] in ("completed", "error"):
                break

            await asyncio.sleep(0.3)
    except WebSocketDisconnect:
        pass


@app.get("/download/{task_id}")
async def download_zip(task_id: str):
    task = tasks.get(task_id)
    if not task or not task.get("zip_path"):
        return HTMLResponse("No disponible", status_code=404)
    return FileResponse(
        task["zip_path"],
        media_type="application/zip",
        filename=Path(task["zip_path"]).name,
    )


def _cleanup_old_tasks():
    keep = 10
    completed_ids = [
        tid
        for tid, t in tasks.items()
        if t["status"] in ("completed", "error") and t.get("zip_path")
    ]
    if len(completed_ids) > keep:
        for tid in completed_ids[:-keep]:
            tasks.pop(tid, None)


async def _run_download(task_id: str, url: str, playlist_id: str):
    task = tasks[task_id]
    task["status"] = "resolviendo"

    try:
        log.info("Playlist ID: %s", playlist_id)

        playlist_name, tracks = await asyncio.get_event_loop().run_in_executor(
            executor, get_tracks_from_spotify, playlist_id
        )

        if not tracks:
            task["status"] = "error"
            task["error"] = "No se encontraron canciones en la playlist."
            return

        folder_name = _sanitize_name(playlist_name)
        out_dir = DOWNLOADS_DIR / folder_name
        out_dir.mkdir(parents=True, exist_ok=True)
        task["output_dir"] = str(out_dir)

        task["status"] = "downloading"
        task["total"] = len(tracks)
        task["songs"] = [
            {"name": t["name"], "artist": t["artist"], "status": "pending", "error": None}
            for t in tracks
        ]

        library = _load_library()

        for idx, t in enumerate(tracks):
            if task["status"] == "cancelled":
                return

            task["progress"] = idx
            task["current"] = {"name": t["name"], "artist": t["artist"]}

            safe_name = _sanitize_name(t["name"])
            safe_artist = _sanitize_name(t["artist"])
            dest_path = out_dir / f"{safe_artist} - {safe_name}.mp3"

            lib_key = _library_key(t["artist"], t["name"])
            cached_path = library.get(lib_key)

            if cached_path and Path(cached_path).exists():
                if not dest_path.exists():
                    shutil.copy2(cached_path, dest_path)
                task["songs"][idx]["status"] = "already_downloaded"
                continue

            if dest_path.exists():
                task["songs"][idx]["status"] = "already_downloaded"
                library[lib_key] = str(dest_path.resolve())
                _save_library(library)
                continue

            task["songs"][idx]["status"] = "downloading"

            output_template = str(out_dir / f"{safe_artist} - {safe_name}.%(ext)s")

            result = await asyncio.get_event_loop().run_in_executor(
                executor, download_song_yt, t['artist'], t['name'], output_template
            )

            if result:
                task["songs"][idx]["status"] = "completed"
                library[lib_key] = str(result.resolve())
                _save_library(library)
            else:
                task["songs"][idx]["status"] = "failed"
                task["songs"][idx]["error"] = "No se encontró en YouTube"

        task["current"] = None
        task["progress"] = len(tracks)

        # Normalize all downloaded songs
        for idx, t in enumerate(tracks):
            if task["songs"][idx]["status"] == "completed":
                song_path = out_dir / f"{_sanitize_name(t['artist'])} - {_sanitize_name(t['name'])}.mp3"
                if song_path.exists():
                    normalized = song_path.with_stem(song_path.stem + "_norm")
                    _normalize_audio(str(song_path), str(normalized))
                    if normalized.exists():
                        normalized.replace(song_path)

        zip_path = out_dir.with_name(f"{folder_name}.zip")
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for fpath in sorted(out_dir.iterdir()):
                if fpath.is_file() and fpath.suffix == ".mp3":
                    zf.write(fpath, fpath.name)

        task["zip_path"] = str(zip_path)
        task["status"] = "completed"

        completed = sum(1 for s in task["songs"] if s["status"] == "completed")
        cached = sum(1 for s in task["songs"] if s["status"] == "already_downloaded")
        log.info("Tarea %s completada: %d nuevas, %d en caché", task_id, completed, cached)

    except Exception as e:
        task["status"] = "error"
        task["error"] = str(e)[:500]
        log.error("Error: %s", e)


if __name__ == "__main__":
    print("Iniciando SpotiPremium Downloader...")
    print("Abre http://localhost:8000 en tu navegador")
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
