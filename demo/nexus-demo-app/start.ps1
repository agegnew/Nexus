# Starts Harbor Market: the FastAPI backend on 8000 and the web app on 5174, each in its own window.
#
# The backend runs from a virtual environment in backend\.venv, created here on first run, for
# the same reason the shell script does: pip refuses to install into an externally managed
# interpreter, and a failure there used to leave only the web half running with nothing said.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

if (-not (Test-Path "$root\web\node_modules")) {
    Push-Location "$root\web"; npm install; Pop-Location
}

$venv = "$root\backend\.venv"
if (-not (Test-Path $venv)) { python -m venv $venv }
& "$venv\Scripts\python.exe" -m pip install -q --upgrade pip
& "$venv\Scripts\python.exe" -m pip install -q -r "$root\backend\requirements.txt"

Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$root\backend'; & '$venv\Scripts\python.exe' -m uvicorn app.main:app --port 8000"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$root\web'; npm run dev"

Write-Host "Harbor Market: http://localhost:5174 (API on http://127.0.0.1:8000)"
