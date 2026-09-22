from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(prefix="/api/orders", tags=["orders"])

MENU = {"Flat white": 3.8, "Cold brew": 4.2, "Croissant": 2.9, "Banana bread": 3.4}
ORDERS: list[dict] = []
_next_id = 1042


class OrderIn(BaseModel):
    item: str
    quantity: int


@router.get("")
def list_orders():
    return {"menu": MENU, "orders": ORDERS}


@router.post("")
def create_order(order: OrderIn):
    global _next_id
    created = {
        "id": _next_id,
        "item": order.item,
        "quantity": order.quantity,
        "total": round(MENU.get(order.item, 0) * order.quantity, 2),
    }
    _next_id += 1
    ORDERS.append(created)
    return created
