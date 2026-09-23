"""Entry point for the demo API."""

from fastapi import FastAPI

from . import orders, users

app = FastAPI(title="Nexus Demo API")

app.include_router(users.router)
app.include_router(orders.router)


@app.get("/health")
async def health():
    """GHOST ROUTE: useful for uptime checks, but no screen calls it."""
    return {"status": "ok"}
