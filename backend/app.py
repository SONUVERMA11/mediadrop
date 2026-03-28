#!/usr/bin/env python3
"""
DC App – yt-dlp Flask Backend
Deploy to Railway (free tier) – zero cost.
"""
import os, json, subprocess
from flask import Flask, request, jsonify
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

YT_DLP_BIN    = os.environ.get("YT_DLP_BIN", "yt-dlp")
MAX_DURATION  = int(os.environ.get("MAX_DURATION_SECONDS", 7200))

def ytdlp(*args, timeout=60):
    cmd = [YT_DLP_BIN, "--no-warnings", "--geo-bypass"] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

def map_error(stderr):
    s = stderr.lower()
    if "not available in your country" in s: return "GEO_RESTRICTED", 403
    if "private video" in s or "this video is private" in s: return "PRIVATE_CONTENT", 403
    if "unsupported url" in s: return "UNSUPPORTED_URL", 400
    if "rate" in s and "limit" in s: return "RATE_LIMITED", 429
    return stderr.strip() or "PARSE_FAILED", 500

@app.route("/health")
def health():
    return jsonify({"status": "ok", "app": "DC"})

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

    formats = [
        {
            "format_id":    f.get("format_id", ""),
            "ext":          f.get("ext", ""),
            "resolution":   f.get("resolution"),
            "width":        f.get("width"),
            "height":       f.get("height"),
            "fps":          f.get("fps"),
            "vcodec":       f.get("vcodec"),
            "acodec":       f.get("acodec"),
            "abr":          f.get("abr"),
            "vbr":          f.get("vbr"),
            "filesize":     f.get("filesize"),
            "filesize_approx": f.get("filesize_approx"),
            "url":          f.get("url"),
            "format_note":  f.get("format_note"),
        }
        for f in data.get("formats", [])
    ]

    return jsonify({
        "title":     data.get("title", ""),
        "thumbnail": data.get("thumbnail"),
        "duration":  duration,
        "extractor": data.get("extractor"),
        "formats":   formats,
        "error":     None
    })

@app.route("/download-url")
def get_download_url():
    url       = request.args.get("url")
    format_id = request.args.get("format_id")
    if not url or not format_id:
        return jsonify({"error": "Missing parameters"}), 400

    r = ytdlp("--format", format_id, "--get-url", "--no-playlist", url)
    if r.returncode != 0:
        return jsonify({"error": r.stderr.strip() or "Failed to get URL"}), 500

    direct_url = r.stdout.strip().splitlines()[0]

    # filename
    nr = ytdlp("--format", format_id, "--get-filename", "--no-playlist", url)
    filename = nr.stdout.strip().splitlines()[0] if nr.returncode == 0 else None

    return jsonify({"url": direct_url, "filename": filename, "filesize": None})

@app.route("/playlist-info")
def get_playlist_info():
    """
    Returns flat playlist entries (no download).
    Uses --flat-playlist so it returns quickly even for large playlists.
    """
    url = request.args.get("url")
    if not url:
        return jsonify({"error": "Missing url"}), 400

    # Dump playlist without downloading
    r = ytdlp(
        "--dump-json",
        "--flat-playlist",
        "--yes-playlist",
        url,
        timeout=90
    )
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

        # The flat playlist dump mixes playlist header + entries
        if item.get("_type") == "playlist" or "entries" in item:
            playlist_title = item.get("title", "")
            playlist_thumb = item.get("thumbnail")
            # If fully expanded (small playlists), entries are inline
            for e in item.get("entries", []):
                if e and e.get("id"):
                    entries.append(_entry(e))
        else:
            # Each line is a video entry in flat mode
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
        "thumbnail": e.get("thumbnail") or e.get("thumbnails", [{}])[-1].get("url") if e.get("thumbnails") else None,
        "uploader":  e.get("uploader") or e.get("channel", ""),
    }

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8080))
    app.run(host="0.0.0.0", port=port, debug=False)
