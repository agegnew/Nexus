#!/usr/bin/env sh
# Starts Harbor Market: the FastAPI backend on 8000 and the web app on 5174. Ctrl+C stops both.
root="$(cd "$(dirname "$0")" && pwd)"

[ -d "$root/web/node_modules" ] || (cd "$root/web" && npm install)
python3 -m pip install -q -r "$root/backend/requirements.txt" 2>/dev/null || python -m pip install -q -r "$root/backend/requirements.txt"

(cd "$root/backend" && python3 -m uvicorn app.main:app --port 8000 2>/dev/null || python -m uvicorn app.main:app --port 8000) &
backend=$!
trap 'kill $backend 2>/dev/null' EXIT INT TERM

echo "Harbor Market: http://localhost:5174 (API on http://127.0.0.1:8000)"
cd "$root/web" && npm run dev
