#!/usr/bin/env python3
"""
MediaDrop Backend — Proxy-download mode.
  GET /health          — liveness probe
  GET /info            — media metadata + format list (no CDN URLs)
  GET /download        — proxies the actual bytes to the client
  GET /playlist-info   — flat playlist entries
"""

import os
import json
import subprocess
import threading
import requests
from flask import Flask, request, jsonify, Response, stream_with_context
from flask_cors import CORS

app  = Flask(__name__)
CORS(app)

YT_DLP_BIN   = os.environ.get("YT_DLP_BIN",             "yt-dlp")
MAX_DURATION = int(os.environ.get("MAX_DURATION_SECONDS", 7200))

# ── Auto-update yt-dlp in background (non-blocking) ───────────────────────────
def _update_ytdlp():
    try:
        subprocess.run([YT_DLP_BIN, "-U"], capture_output=True, timeout=90)
        print("[startup] yt-dlp update done", flush=True)
    except Exception as e:
        print(f"[startup] yt-dlp update skipped: {e}", flush=True)

threading.Thread(target=_update_ytdlp, daemon=True).start()

# ── yt-dlp runner ─────────────────────────────────────────────────────────────
# YouTube client priority:
#   mweb     — mobile-web, bypasses sign-in/bot checks on most datacenter IPs
#   tv_embedded — TV embed client, no bot check, works for embeddable videos
#   ios      — iOS client, no bot check
#   web      — last resort
_YT_CLIENTS = ["mweb", "tv_embedded", "ios", "web"]

def _ytdlp_cmd(client: str, *extra_args) -> list:
    return [
        YT_DLP_BIN,
        "--no-warnings",
        "--geo-bypass",
        "--no-check-certificates",
        "--extractor-args", f"youtube:player_client={client}",
        *extra_args,
    ]


def ytdlp_with_fallback(*args, timeout=180):
    """
    Run yt-dlp trying multiple YouTube clients until one succeeds.
    Non-YouTube URLs are also handled (extractor-args is ignored by other extractors).
    Returns the subprocess.CompletedProcess of the first success, or the last failure.
    """
    result = None
    for client in _YT_CLIENTS:
        cmd = _ytdlp_cmd(client, *args)
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if result.returncode == 0:
            return result
        stderr_lower = result.stderr.lower()
        # Only retry on bot/sign-in blocks; fail fast on real errors
        if not any(kw in stderr_lower for kw in
                   ["sign in", "bot", "verify", "confirm", "not available",
                    "player_client", "extractor", "nsig", "player"]):
            break   # permanent error — no point retrying with another client
    return result   # return last result (failed)


def map_error(stderr: str) -> tuple:
    s = stderr.lower()
    if "not available in your country" in s:            return "GEO_RESTRICTED",    403
    if "this video is private" in s or "has been removed" in s: return "PRIVATE_CONTENT", 403
    if "sign in" in s or "bot" in s or "verify" in s:  return "PARSE_FAILED",      500
    if "private" in s:                                  return "PRIVATE_CONTENT",   403
    if "unsupported url" in s:                          return "UNSUPPORTED_URL",   400
    if "rate" in s and "limit" in s:                    return "RATE_LIMITED",      429
    msg = stderr.strip()[:500] if stderr.strip() else "PARSE_FAILED"
    return msg,                                                            500


# ── /health ───────────────────────────────────────────────────────────────────
@app.route("/health")
def health():
    return jsonify({"status": "ok", "app": "MediaDrop"})


# ── /info ─────────────────────────────────────────────────────────────────────
@app.route("/info")
def get_info():
    url = request.args.get("url", "").strip()
    if not url:
        return jsonify({"error": "Missing url"}), 400

    r = ytdlp_with_fallback("--dump-json", "--no-playlist", url)

    if r.returncode != 0:
        msg, code = map_error(r.stderr)
        return jsonify({"title": "", "formats": [], "error": msg}), code

    try:
        data = json.loads(r.stdout)
    except json.JSONDecodeError:
        return jsonify({"error": "PARSE_FAILED"}), 500

    duration = data.get("duration") or 0
    if duration > MAX_DURATION:
        return jsonify({"error": f"Content too long (>{MAX_DURATION}s)"}), 413

    formats = []
    for f in data.get("formats", []):
        vcodec    = f.get("vcodec") or "none"
        acodec    = f.get("acodec") or "none"
        has_video = vcodec != "none"
        has_audio = acodec != "none"
        # Skip storyboards and other non-media formats
        if not has_video and not has_audio:
            continue
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
        })

    return jsonify({
        "title":     data.get("title", ""),
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
    Resolves the CDN URL server-side (so IP matches) and proxies the bytes
    directly to the Android app.  No IP-mismatch → no 403.

    Query params:
      url        — original media page URL
      format_id  — yt-dlp format ID
      has_audio  — "true"/"false"
      stream     — "video" or "audio" (only relevant when has_audio=false)
    """
    media_url   = request.args.get("url", "").strip()
    format_id   = request.args.get("format_id", "").strip()
    has_audio   = request.args.get("has_audio", "true").lower() == "true"
    stream_type = request.args.get("stream", "video")

    if not media_url or not format_id:
        return jsonify({"error": "Missing parameters"}), 400

    # Determine format selector
    if has_audio:
        fmt = format_id
    elif stream_type == "audio":
        # Prefer AAC/M4A — the only audio codec Android MediaMuxer can handle
        fmt = "bestaudio[ext=m4a]/bestaudio[acodec=mp4a.40.2]/bestaudio[acodec=aac]/bestaudio"
    else:
        fmt = format_id

    # Resolve CDN URL on the server using the same IP that will fetch it
    r = ytdlp_with_fallback("--format", fmt, "--get-url", "--no-playlist", media_url)

    if r.returncode != 0:
        err = r.stderr.strip()[:300] or "Failed to resolve URL"
        return jsonify({"error": err}), 500

    lines   = [ln.strip() for ln in r.stdout.strip().splitlines() if ln.strip()]
    cdn_url = lines[0] if lines else None

    if not cdn_url:
        return jsonify({"error": "yt-dlp returned empty URL"}), 500

    # Build request headers — match the headers used by yt-dlp so CDN accepts us
    req_headers = {
        "User-Agent":      "Mozilla/5.0 (Linux; Android 11; Pixel 5) "
                           "AppleWebKit/537.36 (KHTML, like Gecko) "
                           "Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept":          "*/*",
        "Accept-Encoding": "identity",
        "Connection":      "keep-alive",
    }
    # Forward Range header for resume support
    if "Range" in request.headers:
        req_headers["Range"] = request.headers["Range"]

    try:
        cdn_resp = requests.get(
            cdn_url,
            headers=req_headers,
            stream=True,
            timeout=(15, 300),  # 5 min max for slow CDNs
            allow_redirects=True,
        )

        resp_headers = {
            "Content-Type":  cdn_resp.headers.get("Content-Type", "application/octet-stream"),
            "Accept-Ranges": "bytes",
        }
        if "Content-Length" in cdn_resp.headers:
            resp_headers["Content-Length"] = cdn_resp.headers["Content-Length"]
        if "Content-Range" in cdn_resp.headers:
            resp_headers["Content-Range"] = cdn_resp.headers["Content-Range"]

        def generate():
            try:
                for chunk in cdn_resp.iter_content(chunk_size=131_072):  # 128 KB
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
        return jsonify({"error": f"CDN fetch error: {str(e)}"}), 502


# ── /playlist-info ────────────────────────────────────────────────────────────
@app.route("/playlist-info")
def get_playlist_info():
    url = request.args.get("url", "").strip()
    if not url:
        return jsonify({"error": "Missing url"}), 400

    r = ytdlp_with_fallback("--dump-json", "--flat-playlist", "--yes-playlist", url, timeout=120)
    if r.returncode != 0:
        msg, code = map_error(r.stderr)
        return jsonify({"title": "", "entries": [], "error": msg}), code

    entries        = []
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
        elif item.get("id"):
            entries.append(_entry(item))

    if not entries:
        return jsonify({"error": "No entries found"}), 404

    return jsonify({
        "title":     playlist_title or f"Playlist ({len(entries)} videos)",
        "thumbnail": playlist_thumb,
        "entries":   entries,
        "error":     None,
    })


def _entry(e: dict) -> dict:
    return {
        "id":        e.get("id", ""),
        "title":     e.get("title") or e.get("ie_key", ""),
        "url":       e.get("url") or e.get("webpage_url", ""),
        "duration":  e.get("duration"),
        "thumbnail": e.get("thumbnail") or (e.get("thumbnails") or [{}])[-1].get("url"),
        "uploader":  e.get("uploader") or e.get("channel", ""),
    }


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8080))
    app.run(host="0.0.0.0", port=port, debug=False)
