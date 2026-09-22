# Starts Harbor Market: the FastAPI backend on 8000 and the web app on 5174, each in its own window.
$root = $PSScriptRoot

if (-not (Test-Path "$root\web\node_modules")) {
    Push-Location "$root\web"; npm install; Pop-Location
}
python -m pip install -q -r "$root\backend\requirements.txt"

Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$root\backend'; python -m uvicorn app.main:app --port 8000"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$root\web'; npm run dev"

Write-Host "Harbor Market: http://localhost:5174 (API on http://127.0.0.1:8000)"
