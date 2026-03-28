#!/usr/bin/env python3
"""
Quick smoke test for the yt-dlp Flask API.
Run this after deploying your backend to check all endpoints work.

Usage:
    pip install requests
    python test_backend.py https://your-app.railway.app
"""
import sys
import requests

BASE_URL = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"
TEST_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"  # "Me at the zoo" – public domain

def test(name, url, expected_key):
    try:
        r = requests.get(url, timeout=60)
        data = r.json()
        ok = expected_key in data and data.get("error") is None
        icon = "✅" if ok else "❌"
        print(f"{icon} {name}: HTTP {r.status_code} | {expected_key}={'PRESENT' if expected_key in data else 'MISSING'}")
        if not ok and "error" in data:
            print(f"   Error: {data['error']}")
        return ok
    except Exception as e:
        print(f"❌ {name}: FAILED – {e}")
        return False

print(f"\n🔍 Testing backend at {BASE_URL}\n")
results = [
    test("Health check", f"{BASE_URL}/health", "status"),
    test("Media info",   f"{BASE_URL}/info?url={TEST_URL}", "title"),
]

passed = sum(results)
total = len(results)
print(f"\n{'✅' if passed == total else '⚠️'} {passed}/{total} tests passed\n")
