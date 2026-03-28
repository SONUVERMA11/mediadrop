#!/usr/bin/env python3
"""
DC App – yt-dlp Flask Backend
"""
import os, json, subprocess
from flask import Flask, request, jsonify
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

YT_DLP_BIN   = os.environ.get("YT_DLP_BIN", "yt-dlp")
MAX_DURATION = int(os.environ.get("MAX_DURATION_SECONDS", 7200))

def ytdlp(*args, timeout=180):
    cmd = [YT_DLP_BIN, "--no-warnings", "--geo-bypass",
           "--no-check-certificates"] + list(args)
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
            "has_audio":       has_audio,   # ← KEY: tells app if audio is bundled
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

@app.route("/download-url")
def get_download_url():
    """
    Returns the direct CDN URL(s) for a format.
    If the format is video-only (has_audio=false), also returns
    audio_url so the app can download+merge for maximum quality.
    """
    url       = request.args.get("url")
    format_id = request.args.get("format_id")
    has_audio = request.args.get("has_audio", "true").lower() == "true"

    if not url or not format_id:
        return jsonify({"error": "Missing parameters"}), 400

    if has_audio:
        # Format already contains audio — get single CDN URL
        r = ytdlp("--format", format_id, "--get-url", "--no-playlist", url)
        if r.returncode != 0:
            return jsonify({"error": r.stderr.strip() or "Failed to get URL"}), 500

        lines = [l.strip() for l in r.stdout.strip().splitlines() if l.strip()]
        video_url = lines[0] if lines else None
        audio_url = lines[1] if len(lines) > 1 else None   # some formats return 2 lines

        if not video_url:
            return jsonify({"error": "yt-dlp returned empty URL for this format"}), 500

        return jsonify({
            "url":       video_url,
            "audio_url": audio_url,
            "filesize":  None
        })
    else:
        # Video-only format — fetch video URL and best audio URL separately
        # so the app can download both in parallel and merge locally
        r_video = ytdlp("--format", format_id, "--get-url", "--no-playlist", url)
        # Prefer m4a (AAC) over opus so MediaMuxer can mux it into MP4 without issues
        r_audio = ytdlp("--format", "bestaudio[ext=m4a]/bestaudio[acodec=aac]/bestaudio",
                        "--get-url", "--no-playlist", url)

        video_url = r_video.stdout.strip().splitlines()[0].strip() if r_video.returncode == 0 else None
        audio_url = r_audio.stdout.strip().splitlines()[0].strip() if r_audio.returncode == 0 else None

        if not video_url:
            return jsonify({"error": "Could not resolve video URL"}), 500

        if not audio_url:
            # Return video-only if no audio URL found
            # Client will still download the video, just without audio merge
            return jsonify({
                "url":       video_url,
                "audio_url": None,
                "filesize":  None
            })

        return jsonify({
            "url":       video_url,
            "audio_url": audio_url,   # non-null → app merges video+audio
            "filesize":  None
        })

@app.route("/playlist-info")
def get_playlist_info():
    """Returns flat playlist entries for batch download."""
    url = request.args.get("url")
    if not url:
        return jsonify({"error": "Missing url"}), 400

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
