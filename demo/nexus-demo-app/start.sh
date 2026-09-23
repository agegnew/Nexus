#!/usr/bin/env sh
# Starts Harbor Market: the FastAPI backend on 8000 and the web app on 5174. Ctrl+C stops both.
#
# The backend runs from a virtual environment in backend/.venv, created here on first run.
# It used to `pip install` straight into whatever python3 was on PATH, with stderr sent to
# /dev/null and a fallback to `python`. On any current machine that fails twice over: pip
# refuses to touch an externally managed interpreter (PEP 668), and `python` has not existed
# on macOS since the system Python 2 was removed. Both failures were hidden, so the script
# printed its usual "Harbor Market: http://localhost:5174" line and only the web half was up.
set -e
root="$(cd "$(dirname "$0")" && pwd)"

[ -d "$root/web/node_modules" ] || (cd "$root/web" && npm install)

venv="$root/backend/.venv"
[ -d "$venv" ] || python3 -m venv "$venv"
"$venv/bin/python" -m pip install -q --upgrade pip
"$venv/bin/python" -m pip install -q -r "$root/backend/requirements.txt"

(cd "$root/backend" && "$venv/bin/python" -m uvicorn app.main:app --port 8000) &
backend=$!
trap 'kill $backend 2>/dev/null' EXIT INT TERM

echo "Harbor Market: http://localhost:5174 (API on http://127.0.0.1:8000)"
cd "$root/web" && npm run dev
