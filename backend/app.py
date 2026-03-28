#!/usr/bin/env python3
"""
MediaDrop – yt-dlp Flask Backend
Architecture: Proxy-download mode.
  - /info          → returns media metadata + format list
  - /download      → streams the actual file bytes to the client (proxy mode)
  - /playlist-info → returns flat playlist info
  - /health        → liveness probe

WHY PROXY MODE:
  yt-dlp CDN URLs (especially YouTube) are signed to the server IP.
  Passing raw CDN URLs to the Android app causes 403 Forbidden because
  the device IP differs from the Railway server IP that fetched the URL.
  Solution: backend fetches the bytes and streams them directly to the app.
"""

import os
import json
import subprocess
import threading
import requests
from flask import Flask, request, jsonify, Response, stream_with_context
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

YT_DLP_BIN   = os.environ.get("YT_DLP_BIN", "yt-dlp")
MAX_DURATION = int(os.environ.get("MAX_DURATION_SECONDS", 7200))

# Browser-like headers to pass through to CDN
DOWNLOAD_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    ),
    "Accept": "*/*",
    "Accept-Encoding": "identity",
    "Connection": "keep-alive",
}

# ── yt-dlp helper ──────────────────────────────────────────────────────────────
def ytdlp(*args, timeout=180):
    cmd = [
        YT_DLP_BIN,
        "--no-warnings",
        "--geo-bypass",
        "--no-check-certificates",
    ] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def map_error(stderr):
    s = stderr.lower()
    if "not available in your country" in s: return "GEO_RESTRICTED", 403
    if "private video" in s or "this video is private" in s: return "PRIVATE_CONTENT", 403
    if "unsupported url" in s: return "UNSUPPORTED_URL", 400
    if "rate" in s and "limit" in s: return "RATE_LIMITED", 429
    return stderr.strip() or "PARSE_FAILED", 500


# ── /health ────────────────────────────────────────────────────────────────────
@app.route("/health")
def health():
    return jsonify({"status": "ok", "app": "MediaDrop"})


# ── /info ──────────────────────────────────────────────────────────────────────
@app.route("/info")
def get_info():
    url = request.args.get("url")
    if not url:
        return jsonify({"error": "Missing url"}), 400

    r = ytdlp("--dump-json", "--no-playlist", url)
    if r.returncode != 0:
        msg, code = map_error(r.stderr)
        return jsonify({"title": "", "formats": [], "error": msg}), code

    try:
        data = json.loads(r.stdout)
    except json.JSONDecodeError:
        return jsonify({"error": "PARSE_FAILED"}), 500

    duration = data.get("duration", 0) or 0
    if duration > MAX_DURATION:
        return jsonify({"error": f"Content too long (>{MAX_DURATION}s)"}), 413

    formats = []
    for f in data.get("formats", []):
        vcodec = f.get("vcodec") or "none"
        acodec = f.get("acodec") or "none"
        has_video = vcodec != "none"
        has_audio = acodec != "none"

        formats.append({
            "format_id":       f.get("format_id", ""),
            "ext":             f.get("ext", ""),
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
            # Note: we do NOT expose the raw CDN url — proxy mode handles delivery
        })

    return jsonify({
        "title":     data.get("title", ""),
        "thumbnail": data.get("thumbnail"),
        "duration":  duration,
        "extractor": data.get("extractor"),
        "formats":   formats,
        "error":     None
    })


# ── /download ─────────────────────────────────────────────────────────────────
@app.route("/download")
def proxy_download():
    """
    Proxy-streams the video/audio bytes directly to the Android app.

    Params:
      url       – original media page URL
      format_id – yt-dlp format ID (e.g. "137" for 1080p video)
      has_audio – "true"/"false"; if false, also fetches & streams best m4a audio
      stream    – "video" or "audio" (used when has_audio=false, to get each separately)

    The Android app makes two requests when has_audio=false:
      /download?url=...&format_id=137&has_audio=false&stream=video
      /download?url=...&format_id=137&has_audio=false&stream=audio
    Then merges them locally with MediaMuxer.
    """
    media_url = request.args.get("url")
    format_id = request.args.get("format_id")
    has_audio = request.args.get("has_audio", "true").lower() == "true"
    stream_type = request.args.get("stream", "video")  # "video" or "audio"

    if not media_url or not format_id:
        return jsonify({"error": "Missing parameters"}), 400

    # Resolve which yt-dlp format to use
    if has_audio:
        fmt = format_id
    else:
        if stream_type == "audio":
            # Best m4a audio (AAC) — compatible with Android MediaMuxer
            fmt = "bestaudio[ext=m4a]/bestaudio[acodec=mp4a.40.2]/bestaudio[acodec=aac]/bestaudio"
        else:
            fmt = format_id

    # Get the direct CDN URL via yt-dlp (on the server, so IP matches)
    r = ytdlp("--format", fmt, "--get-url", "--no-playlist", media_url)
    if r.returncode != 0:
        return jsonify({"error": r.stderr.strip() or "Failed to resolve URL"}), 500

    lines = [l.strip() for l in r.stdout.strip().splitlines() if l.strip()]
    cdn_url = lines[0] if lines else None

    if not cdn_url:
        return jsonify({"error": "yt-dlp returned empty URL"}), 500

    # Forward the Range header from the Android app (for chunked/resume downloads)
    req_headers = dict(DOWNLOAD_HEADERS)
    range_header = request.headers.get("Range")
    if range_header:
        req_headers["Range"] = range_header

    # Stream from CDN → Flask → Android app
    try:
        cdn_resp = requests.get(
            cdn_url,
            headers=req_headers,
            stream=True,
            timeout=(15, 120),
            allow_redirects=True,
        )

        # Determine content type
        content_type = cdn_resp.headers.get("Content-Type", "application/octet-stream")

        # Build response headers to pass back
        resp_headers = {
            "Content-Type": content_type,
            "Accept-Ranges": "bytes",
        }
        if "Content-Length" in cdn_resp.headers:
            resp_headers["Content-Length"] = cdn_resp.headers["Content-Length"]
        if "Content-Range" in cdn_resp.headers:
            resp_headers["Content-Range"] = cdn_resp.headers["Content-Range"]

        status_code = cdn_resp.status_code  # 200 or 206

        def generate():
            try:
                for chunk in cdn_resp.iter_content(chunk_size=65536):  # 64 KB
                    if chunk:
                        yield chunk
            finally:
                cdn_resp.close()

        return Response(
            stream_with_context(generate()),
            status=status_code,
            headers=resp_headers,
            direct_passthrough=True,
        )

    except requests.exceptions.RequestException as e:
        return jsonify({"error": f"CDN fetch failed: {str(e)}"}), 502


# ── /playlist-info ────────────────────────────────────────────────────────────
@app.route("/playlist-info")
def get_playlist_info():
    """Returns flat playlist entries for batch download."""
    url = request.args.get("url")
    if not url:
        return jsonify({"error": "Missing url"}), 400

    r = ytdlp("--dump-json", "--flat-playlist", "--yes-playlist", url, timeout=120)
    if r.returncode != 0:
        msg, code = map_error(r.stderr)
        return jsonify({"title": "", "entries": [], "error": msg}), code

    entries = []
    playlist_title = ""
    playlist_thumb = None

    for line in r.stdout.strip().splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue

        if item.get("_type") == "playlist" or "entries" in item:
            playlist_title = item.get("title", "")
            playlist_thumb = item.get("thumbnail")
            for e in item.get("entries", []):
                if e and e.get("id"):
                    entries.append(_entry(e))
        else:
            if item.get("id"):
                entries.append(_entry(item))

    if not entries:
        return jsonify({"error": "No entries found — may not be a playlist"}), 404

    if not playlist_title and entries:
        playlist_title = f"Playlist ({len(entries)} videos)"

    return jsonify({
        "title":     playlist_title,
        "thumbnail": playlist_thumb,
        "entries":   entries,
        "error":     None
    })


def _entry(e):
    return {
        "id":        e.get("id", ""),
        "title":     e.get("title", "") or e.get("ie_key", ""),
        "url":       e.get("url", "") or e.get("webpage_url", ""),
        "duration":  e.get("duration"),
        "thumbnail": e.get("thumbnail") or (e.get("thumbnails") or [{}])[-1].get("url"),
        "uploader":  e.get("uploader") or e.get("channel", ""),
    }


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8080))
    app.run(host="0.0.0.0", port=port, debug=False)
