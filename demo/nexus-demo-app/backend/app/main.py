from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from . import orders, users

app = FastAPI(title="Harbor Market")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
app.include_router(users.router)
app.include_router(orders.router)


# Nothing in the web app calls this, so Nexus shows it as a ghost route.
@app.get("/health")
def health():
    return {"ok": True}
