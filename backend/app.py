#!/usr/bin/env python3
"""
DC App – yt-dlp Flask Backend
"""
import os, json, subprocess, logging
from flask import Flask, request, jsonify
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("dc-backend")

YT_DLP_BIN   = os.environ.get("YT_DLP_BIN", "yt-dlp")
MAX_DURATION = int(os.environ.get("MAX_DURATION_SECONDS", 7200))

# Standard browser headers — CDNs (YouTube, etc.) require these to serve content
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
)
DEFAULT_HEADERS = {
    "User-Agent": BROWSER_UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-us,en;q=0.5",
}

def ytdlp(*args, timeout=90):
    cmd = [YT_DLP_BIN, "--no-warnings", "--geo-bypass",
           "--user-agent", BROWSER_UA] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

def map_error(stderr):
    s = stderr.lower()
    if "not available in your country" in s: return "GEO_RESTRICTED", 403
    if "private video" in s or "this video is private" in s: return "PRIVATE_CONTENT", 403
    if "unsupported url" in s: return "UNSUPPORTED_URL", 400
    if "rate" in s and "limit" in s: return "RATE_LIMITED", 429
    if "sign in" in s or "login" in s: return "LOGIN_REQUIRED", 403
    return stderr.strip() or "PARSE_FAILED", 500

@app.route("/health")
def health():
    return jsonify({"status": "ok", "app": "DC"})

@app.route("/info")
def get_info():
    url = request.args.get("url")
    if not url:
        return jsonify({"error": "Missing url"}), 400

    log.info(f"Fetching info for: {url}")
    r = ytdlp("--dump-json", "--no-playlist", url)
    if r.returncode != 0:
        log.warning(f"yt-dlp /info failed: {r.stderr[:200]}")
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
            "url":             f.get("url"),
            "format_note":     f.get("format_note"),
        })

    return jsonify({
        "title":     data.get("title", ""),
        "thumbnail": data.get("thumbnail"),
        "duration":  duration,
        "extractor": data.get("extractor"),
        "formats":   formats,
        "error":     None
    })


def _resolve_format(format_spec, url):
    """
    Resolve a format to its CDN URL and http_headers.
    Uses --dump-json --format for reliability (returns both url + headers).
    Falls back to --get-url on failure.
    Returns (cdn_url: str|None, headers: dict, filesize: int|None)
    """
    # Primary: --dump-json gives us both the CDN URL and required headers
    r = ytdlp("--format", format_spec, "--dump-json", "--no-playlist", url,
              timeout=60)
    if r.returncode == 0:
        try:
            data = json.loads(r.stdout)
            cdn_url  = data.get("url")
            headers  = data.get("http_headers") or DEFAULT_HEADERS
            filesize = data.get("filesize") or data.get("filesize_approx")
            if cdn_url:
                return cdn_url, headers, filesize
        except (json.JSONDecodeError, KeyError):
            pass

    # Fallback: --get-url is faster but doesn't return headers
    log.info(f"Falling back to --get-url for format {format_spec}")
    r2 = ytdlp("--format", format_spec, "--get-url", "--no-playlist", url)
    if r2.returncode == 0:
        lines = [l.strip() for l in r2.stdout.strip().splitlines() if l.strip()]
        if lines:
            return lines[0], DEFAULT_HEADERS, None

    return None, DEFAULT_HEADERS, None


@app.route("/download-url")
def get_download_url():
    """
    Returns the direct CDN URL(s) for a format, along with the HTTP headers
    required to download from that CDN (User-Agent, etc.).
    If the format is video-only (has_audio=false), also returns
    audio_url so the app can download+merge for maximum quality.
    """
    url       = request.args.get("url")
    format_id = request.args.get("format_id")
    has_audio = request.args.get("has_audio", "true").lower() == "true"

    if not url or not format_id:
        return jsonify({"error": "Missing parameters"}), 400

    log.info(f"Resolving download URL: format={format_id} has_audio={has_audio} url={url}")

    if has_audio:
        # Format already contains audio — single CDN URL
        cdn_url, headers, filesize = _resolve_format(format_id, url)
        if not cdn_url:
            return jsonify({"error": "Failed to resolve download URL"}), 500

        return jsonify({
            "url":       cdn_url,
            "audio_url": None,
            "headers":   headers,
            "filesize":  filesize
        })
    else:
        # Video-only format — resolve video + best audio separately
        video_url, headers, v_filesize = _resolve_format(format_id, url)
        audio_url, _, _                = _resolve_format(
            "bestaudio[ext=m4a]/bestaudio", url)

        if not video_url:
            return jsonify({"error": "Could not resolve video URL"}), 500

        return jsonify({
            "url":       video_url,
            "audio_url": audio_url,
            "headers":   headers,
            "filesize":  v_filesize
        })


@app.route("/playlist-info")
def get_playlist_info():
    """Returns flat playlist entries for batch download."""
    url = request.args.get("url")
    if not url:
        return jsonify({"error": "Missing url"}), 400

    log.info(f"Fetching playlist: {url}")
    r = ytdlp("--dump-json", "--flat-playlist", "--yes-playlist", url, timeout=90)
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
