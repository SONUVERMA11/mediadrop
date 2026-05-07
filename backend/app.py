#!/usr/bin/env python3
"""
DeepLoader Backend — Proxy-download mode using yt-dlp Python API.
  GET /health          — liveness probe
  GET /info            — media metadata + format list
  GET /download        — proxies media bytes to the client
  GET /playlist-info   — flat playlist entries
"""

import os
import threading
from urllib.parse import urlparse

import requests
import yt_dlp
from flask import Flask, request, jsonify, Response, stream_with_context
from flask_cors import CORS

app  = Flask(__name__)
CORS(app)

MAX_DURATION = int(os.environ.get("MAX_DURATION_SECONDS", 7200))
YTDLP_COOKIE_FILE = os.environ.get("YTDLP_COOKIES_FILE", "").strip() or None
YTDLP_COOKIES_BROWSER = os.environ.get("YTDLP_COOKIES_BROWSER", "").strip() or None
YTDLP_COOKIES_PROFILE = os.environ.get("YTDLP_COOKIES_PROFILE", "").strip() or None
YTDLP_COOKIES_KEYRING = os.environ.get("YTDLP_COOKIES_KEYRING", "").strip() or None
YTDLP_COOKIES_CONTAINER = os.environ.get("YTDLP_COOKIES_CONTAINER", "").strip() or None
YTDLP_YOUTUBE_PO_TOKEN = os.environ.get("YTDLP_YOUTUBE_PO_TOKEN", "").strip() or None
YTDLP_YOUTUBE_VISITOR_DATA = os.environ.get("YTDLP_YOUTUBE_VISITOR_DATA", "").strip() or None
YTDLP_PROXY = os.environ.get("YTDLP_PROXY", "").strip() or None
YTDLP_USER_AGENT = os.environ.get(
    "YTDLP_USER_AGENT",
    (
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/136.0.0.0 Mobile Safari/537.36"
    ),
).strip()

# ── Auto-update yt-dlp once in background ────────────────────────────────────
def _update():
    try:
        from yt_dlp.update import run_update
        run_update()
        print("[startup] yt-dlp updated", flush=True)
    except Exception as e:
        print(f"[startup] yt-dlp update skipped: {e}", flush=True)

threading.Thread(target=_update, daemon=True).start()

# ── yt-dlp base options ───────────────────────────────────────────────────────
# Let yt-dlp's default YouTube client selection run first, then fall back to
# a curated list of supported clients if extraction still fails.
_YT_CLIENTS = [None, "tv", "android_vr", "ios", "android", "mweb", "web_safari", "web"]


def _is_youtube_url(url: str) -> bool:
    try:
        host = (urlparse(url).hostname or "").lower()
    except Exception:
        return False
    return (
        host.endswith("youtube.com")
        or host.endswith("youtu.be")
        or host.endswith("youtube-nocookie.com")
    )


def _has_cookie_auth() -> bool:
    return bool(YTDLP_COOKIE_FILE or YTDLP_COOKIES_BROWSER)


def _cookies_from_browser_spec():
    if not YTDLP_COOKIES_BROWSER:
        return None

    spec = [
        YTDLP_COOKIES_BROWSER,
        YTDLP_COOKIES_PROFILE,
        YTDLP_COOKIES_KEYRING,
        YTDLP_COOKIES_CONTAINER,
    ]
    while len(spec) > 1 and spec[-1] is None:
        spec.pop()
    return tuple(spec)


def _youtube_extractor_args(client: str | None) -> dict:
    args = {}
    if client:
        args["player_client"] = [client]
    if YTDLP_YOUTUBE_PO_TOKEN:
        args["po_token"] = [YTDLP_YOUTUBE_PO_TOKEN]
    if YTDLP_YOUTUBE_VISITOR_DATA:
        args["visitor_data"] = [YTDLP_YOUTUBE_VISITOR_DATA]
        if not _has_cookie_auth():
            args["player_skip"] = ["webpage", "configs"]
    return args


def _base_opts(client: str | None = None) -> dict:
    opts = {
        "quiet":              True,
        "no_warnings":        True,
        "geo_bypass":         True,
        "nocheckcertificate": True,
        "extractor_retries":  3,
        "retries":            3,
        "fragment_retries":   3,
        "socket_timeout":     30,
        "http_headers": {
            "User-Agent": YTDLP_USER_AGENT,
        },
    }

    if YTDLP_PROXY:
        opts["proxy"] = YTDLP_PROXY
    if YTDLP_COOKIE_FILE:
        opts["cookiefile"] = YTDLP_COOKIE_FILE
    else:
        browser_spec = _cookies_from_browser_spec()
        if browser_spec:
            opts["cookiesfrombrowser"] = browser_spec

    youtube_args = _youtube_extractor_args(client)
    if youtube_args:
        extractor_args = {"youtube": youtube_args}
        if YTDLP_YOUTUBE_VISITOR_DATA and not _has_cookie_auth():
            extractor_args["youtubetab"] = {"skip": ["webpage"]}
        opts["extractor_args"] = extractor_args

    return opts


def _should_retry_youtube_client(msg: str) -> bool:
    if any(kw in msg for kw in (
        "unsupported url",
        "private video",
        "this video is private",
        "has been removed",
        "not available in your country",
        "geo restricted",
    )):
        return False

    return any(kw in msg for kw in (
        "sign in",
        "bot",
        "verify",
        "confirm",
        "nsig",
        "player",
        "extractor",
        "po token",
        "serviceintegrity",
        "visitor_data",
        "forbidden",
        "http error 403",
        "requested format is not available",
        "unsupported player client",
    ))


def extract_info_with_fallback(url: str, extra_opts: dict = None) -> dict:
    """
    Try extracting info with multiple YouTube clients.
    Returns the info dict on success, raises on failure.
    """
    is_youtube = _is_youtube_url(url)
    clients = _YT_CLIENTS if is_youtube else [None]
    last_error = None
    for client in clients:
        label = client or "default"
        opts = {**_base_opts(client if is_youtube else None), **(extra_opts or {})}
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=False)
                if info:
                    print(f"[info] client={label} ok url={url[:60]}", flush=True)
                    return ydl.sanitize_info(info)
        except yt_dlp.utils.DownloadError as e:
            msg = str(e).lower()
            last_error = e
            print(f"[info] client={label} failed: {str(e)[:120]}", flush=True)
            if not is_youtube or not _should_retry_youtube_client(msg):
                break
        except Exception as e:
            last_error = e
            msg = str(e).lower()
            print(f"[info] client={label} exception: {e}", flush=True)
            if not is_youtube or not _should_retry_youtube_client(msg):
                break

    raise last_error or Exception("All clients failed")


def classify_error(e: Exception) -> tuple:
    """Map a yt-dlp exception to (user_message, http_code)."""
    msg = str(e).lower()
    if "not available in your country" in msg or "geo" in msg:
        return "GEO_RESTRICTED", 403
    if "this video is private" in msg or "has been removed" in msg:
        return "PRIVATE_CONTENT", 403
    if "private" in msg:
        return "PRIVATE_CONTENT", 403
    if (
        "sign in" in msg
        or "bot" in msg
        or "verify" in msg
        or "confirm" in msg
        or "po token" in msg
        or "serviceintegrity" in msg
    ):
        return "YOUTUBE_AUTH_REQUIRED", 403
    if "unsupported url" in msg:
        return "UNSUPPORTED_URL", 400
    if "rate" in msg and "limit" in msg:
        return "RATE_LIMITED", 429
    # Return the raw error (first 300 chars) so the app can show it
    raw = str(e)
    # Strip the "ERROR: " prefix yt-dlp adds
    if raw.startswith("ERROR: "):
        raw = raw[7:]
    return raw[:300] or "PARSE_FAILED", 500


# ── /health ───────────────────────────────────────────────────────────────────
@app.route("/health")
def health():
    return jsonify({
        "status": "ok",
        "ytdlp": yt_dlp.version.__version__,
        "youtube_auth": {
            "cookie_file": bool(YTDLP_COOKIE_FILE),
            "cookies_from_browser": bool(YTDLP_COOKIES_BROWSER),
            "po_token": bool(YTDLP_YOUTUBE_PO_TOKEN),
            "visitor_data": bool(YTDLP_YOUTUBE_VISITOR_DATA),
        },
    })


# ── /info ─────────────────────────────────────────────────────────────────────
@app.route("/info")
def get_info():
    url = request.args.get("url", "").strip()
    if not url:
        return jsonify({"error": "Missing url"}), 400

    print(f"[/info] url={url[:80]}", flush=True)

    try:
        data = extract_info_with_fallback(url, {
            "noplaylist": True,
            "skip_download": True,
        })
    except Exception as e:
        print(f"[/info] FAILED: {e}", flush=True)
        msg, code = classify_error(e)
        return jsonify({"title": "", "formats": [], "error": msg}), code

    duration = data.get("duration") or 0
    if duration > MAX_DURATION:
        return jsonify({"error": f"Content too long (>{MAX_DURATION}s)"}), 413

    formats = []
    for f in data.get("formats") or []:
        try:
            vcodec    = f.get("vcodec") or "none"
            acodec    = f.get("acodec") or "none"
            has_video = vcodec != "none"
            has_audio = acodec != "none"
            ext       = f.get("ext") or ""

            # Skip storyboards and manifest files
            if ext in ("mhtml", "vtt") or (not has_video and not has_audio):
                continue

            formats.append({
                "format_id":       str(f.get("format_id") or ""),
                "ext":             ext,
                "resolution":      f.get("resolution"),
                "width":           f.get("width"),
                "height":          f.get("height"),
                "fps":             f.get("fps"),
                "vcodec":          vcodec,
                "acodec":          acodec,
                "has_video":       has_video,
                "has_audio":       has_audio,
                "abr":             f.get("abr"),
                "vbr":             f.get("vbr"),
                "filesize":        f.get("filesize"),
                "filesize_approx": f.get("filesize_approx"),
                "format_note":     f.get("format_note"),
            })
        except Exception:
            continue  # skip malformed format entries

    return jsonify({
        "title":     data.get("title") or "",
        "thumbnail": data.get("thumbnail"),
        "duration":  duration,
        "extractor": data.get("extractor"),
        "formats":   formats,
        "error":     None,
    })


# ── /download ─────────────────────────────────────────────────────────────────
@app.route("/download")
def proxy_download():
    """
    Resolves CDN URL server-side (IP matches) and proxies bytes to the app.
    """
    media_url   = request.args.get("url", "").strip()
    format_id   = request.args.get("format_id", "").strip()
    has_audio   = request.args.get("has_audio", "true").lower() == "true"
    stream_type = request.args.get("stream", "video")

    if not media_url or not format_id:
        return jsonify({"error": "Missing parameters"}), 400

    print(f"[/download] fmt={format_id} has_audio={has_audio} stream={stream_type} url={media_url[:60]}", flush=True)

    # Determine format selector
    if has_audio:
        fmt = format_id
    elif stream_type == "audio":
        fmt = "bestaudio[ext=m4a]/bestaudio[acodec~='^(mp4a|aac)']"
    else:
        fmt = format_id

    # Get CDN URL using the Python API
    cdn_url = None
    last_err = None
    is_youtube = _is_youtube_url(media_url)
    for client in (_YT_CLIENTS if is_youtube else [None]):
        label = client or "default"
        opts = {
            **_base_opts(client if is_youtube else None),
            "noplaylist":  True,
            "skip_download": True,
            "format":      fmt,
        }
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(media_url, download=False)
                info = ydl.sanitize_info(info)
                # For merged/single formats: url is directly on info
                cdn_url = info.get("url")
                # For format-selected: find matching format
                if not cdn_url:
                    selected = info.get("requested_formats") or info.get("formats") or []
                    for f in selected:
                        if f.get("format_id") == format_id or stream_type == "audio":
                            cdn_url = f.get("url")
                            break
                if cdn_url:
                    print(f"[/download] client={label} cdn_url resolved", flush=True)
                    break
        except yt_dlp.utils.DownloadError as e:
            last_err = e
            msg = str(e).lower()
            print(f"[/download] client={label} failed: {str(e)[:120]}", flush=True)
            if not is_youtube or not _should_retry_youtube_client(msg):
                break
        except Exception as e:
            last_err = e
            msg = str(e).lower()
            print(f"[/download] client={label} exception: {e}", flush=True)
            if not is_youtube or not _should_retry_youtube_client(msg):
                break

    if not cdn_url:
        err = str(last_err)[:300] if last_err else "Failed to resolve CDN URL"
        print(f"[/download] FAILED: {err}", flush=True)
        return jsonify({"error": err}), 500

    # Proxy CDN bytes to Android
    req_headers = {
        "User-Agent":      YTDLP_USER_AGENT,
        "Accept":          "*/*",
        "Accept-Encoding": "identity",
    }
    if "Range" in request.headers:
        req_headers["Range"] = request.headers["Range"]

    try:
        cdn_resp = requests.get(
            cdn_url,
            headers=req_headers,
            stream=True,
            timeout=(15, 300),
            allow_redirects=True,
        )

        resp_headers = {
            "Content-Type":  cdn_resp.headers.get("Content-Type", "application/octet-stream"),
            "Accept-Ranges": "bytes",
        }
        for h in ("Content-Length", "Content-Range"):
            if h in cdn_resp.headers:
                resp_headers[h] = cdn_resp.headers[h]

        def generate():
            try:
                for chunk in cdn_resp.iter_content(chunk_size=131_072):
                    if chunk:
                        yield chunk
            finally:
                cdn_resp.close()

        return Response(
            stream_with_context(generate()),
            status=cdn_resp.status_code,
            headers=resp_headers,
            direct_passthrough=True,
        )

    except requests.exceptions.RequestException as e:
        print(f"[/download] CDN fetch error: {e}", flush=True)
        return jsonify({"error": f"CDN error: {str(e)[:200]}"}), 502


# ── /playlist-info ────────────────────────────────────────────────────────────
@app.route("/playlist-info")
def get_playlist_info():
    url = request.args.get("url", "").strip()
    if not url:
        return jsonify({"error": "Missing url"}), 400

    print(f"[/playlist-info] url={url[:80]}", flush=True)

    try:
        data = extract_info_with_fallback(url, {
            "extract_flat": "in_playlist",
            "playlistend":  200,
        })
    except Exception as e:
        msg, code = classify_error(e)
        return jsonify({"title": "", "entries": [], "error": msg}), code

    raw_entries = []
    if "entries" in data:
        raw_entries = [e for e in (data.get("entries") or []) if e]
    elif data.get("_type") == "video":
        raw_entries = [data]

    if not raw_entries:
        return jsonify({"error": "No entries found"}), 404

    entries = [_entry(e) for e in raw_entries if e.get("id")]

    return jsonify({
        "title":     data.get("title") or f"Playlist ({len(entries)} videos)",
        "thumbnail": data.get("thumbnail"),
        "entries":   entries,
        "error":     None,
    })


def _entry(e: dict) -> dict:
    thumb = e.get("thumbnail")
    if not thumb:
        thumbs = e.get("thumbnails") or []
        thumb  = thumbs[-1].get("url") if thumbs else None
    return {
        "id":        e.get("id") or "",
        "title":     e.get("title") or e.get("ie_key") or "",
        "url":       e.get("url") or e.get("webpage_url") or "",
        "duration":  e.get("duration"),
        "thumbnail": thumb,
        "uploader":  e.get("uploader") or e.get("channel") or "",
    }


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8080))
    app.run(host="0.0.0.0", port=port, debug=False)
