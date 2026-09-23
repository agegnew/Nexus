"""Order routes. Both are wired to the order screen."""

from fastapi import APIRouter

router = APIRouter(prefix="/api/orders", tags=["orders"])


@router.get("")
async def list_orders():
    """Called by OrderPage.tsx."""
    return [{"id": "o-1", "userId": "1", "total": 49}]


@router.post("")
async def create_order(payload: dict):
    """Called by OrderPage.tsx when the user places an order."""
    return {"id": "o-2", **payload}
