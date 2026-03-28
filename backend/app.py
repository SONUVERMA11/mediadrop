#!/usr/bin/env python3
"""
MediaDrop yt-dlp API Backend
Deploy to Railway, Render, or any VPS supporting Python.
"""
import os
import json
import subprocess
import tempfile
from flask import Flask, request, jsonify, send_file, abort
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

YT_DLP_BIN = os.environ.get("YT_DLP_BIN", "yt-dlp")
MAX_DURATION = int(os.environ.get("MAX_DURATION_SECONDS", 7200))  # 2 hours default


def run_ytdlp(args: list) -> subprocess.CompletedProcess:
    cmd = [YT_DLP_BIN] + args
    return subprocess.run(cmd, capture_output=True, text=True, timeout=120)


@app.route("/health")
def health():
    return jsonify({"status": "ok"})


@app.route("/info")
def get_info():
    """
    GET /info?url=<media_url>
    Returns yt-dlp --dump-json output parsed into MediaInfoDto format.
    """
    url = request.args.get("url")
    if not url:
        return jsonify({"error": "Missing 'url' parameter"}), 400

    result = run_ytdlp([
        "--dump-json",
        "--no-playlist",
        "--no-warnings",
        "--geo-bypass",
        url
    ])

    if result.returncode != 0:
        error_msg = result.stderr.strip()
        # Map known yt-dlp errors to friendly codes
        if "is not available in your country" in error_msg:
            return jsonify({"title": "", "formats": [], "error": "GEO_RESTRICTED"}), 403
        if "Private video" in error_msg or "This video is private" in error_msg:
            return jsonify({"title": "", "formats": [], "error": "PRIVATE_CONTENT"}), 403
        if "Unsupported URL" in error_msg:
            return jsonify({"title": "", "formats": [], "error": "UNSUPPORTED_URL"}), 400
        return jsonify({"title": "", "formats": [], "error": error_msg or "PARSE_FAILED"}), 500

    try:
        data = json.loads(result.stdout)
    except json.JSONDecodeError:
        return jsonify({"title": "", "formats": [], "error": "PARSE_FAILED"}), 500

    # Check duration limit
    duration = data.get("duration", 0) or 0
    if duration > MAX_DURATION:
        return jsonify({"error": f"Content too long (>{MAX_DURATION}s)"}), 413

    # Build response in MediaInfoDto shape expected by Android app
    formats = []
    for fmt in data.get("formats", []):
        formats.append({
            "format_id": fmt.get("format_id", ""),
            "ext": fmt.get("ext", ""),
            "resolution": fmt.get("resolution"),
            "width": fmt.get("width"),
            "height": fmt.get("height"),
            "fps": fmt.get("fps"),
            "vcodec": fmt.get("vcodec"),
            "acodec": fmt.get("acodec"),
            "abr": fmt.get("abr"),
            "vbr": fmt.get("vbr"),
            "filesize": fmt.get("filesize"),
            "filesize_approx": fmt.get("filesize_approx"),
            "url": fmt.get("url"),
            "format_note": fmt.get("format_note"),
        })

    return jsonify({
        "title": data.get("title", ""),
        "thumbnail": data.get("thumbnail"),
        "duration": duration,
        "extractor": data.get("extractor"),
        "formats": formats,
        "error": None
    })


@app.route("/download-url")
def get_download_url():
    """
    GET /download-url?url=<media_url>&format_id=<id>
    Returns the direct CDN URL for the specified format so the Android app
    can download it directly using OkHttp/DownloadManager.
    """
    url = request.args.get("url")
    format_id = request.args.get("format_id")
    if not url or not format_id:
        return jsonify({"error": "Missing parameters"}), 400

    result = run_ytdlp([
        "--format", format_id,
        "--get-url",
        "--no-playlist",
        "--no-warnings",
        url
    ])

    if result.returncode != 0:
        return jsonify({"error": result.stderr.strip() or "Failed to get URL"}), 500

    direct_url = result.stdout.strip().splitlines()[0]

    # Attempt to get filename
    name_result = run_ytdlp([
        "--format", format_id,
        "--get-filename",
        "--no-playlist",
        url
    ])
    filename = name_result.stdout.strip().splitlines()[0] if name_result.returncode == 0 else None

    return jsonify({"url": direct_url, "filename": filename, "filesize": None})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8080))
    app.run(host="0.0.0.0", port=port, debug=False)
