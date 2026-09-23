# Harbor Market (Nexus demo app)

A small coffee-shop app the Nexus Swarm tests on stage. It is a React front end (`web/`, port 5174)
and a FastAPI back end (`backend/`, port 8000).

Three bugs are left in on purpose, so the demo finds something real every time:

| Where | Bug | What the swarm sees |
|---|---|---|
| `web/src/Settings.tsx` | Calls `PUT /api/users/{id}/settings`, which the backend never declares | Save does nothing, request returns 404 |
| `web/src/Analytics.tsx` | Calls `GET /api/analytics/summary`, which does not exist, then reads `.toFixed` on the error | The whole page crashes |
| `web/src/OrderPage.tsx` | Sends `parseInt("")` (NaN, so `null`) and shows the 422 error body as if it were an order | "Order #undefined placed" |

The Nexus Map also shows these as broken wires, plus two ghost routes (`GET /health` and
`DELETE /api/users/{user_id}`).

## Run it

Windows: `./start.ps1`. macOS/Linux: `./start.sh`. Or run it by hand:

```
cd backend && pip install -r requirements.txt && python -m uvicorn app.main:app --port 8000
cd web && npm install && npm run dev        # http://localhost:5174
```
