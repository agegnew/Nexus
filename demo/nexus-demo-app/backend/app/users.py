"""User routes. Two of these are called by the frontend; one is not."""

from fastapi import APIRouter

router = APIRouter(prefix="/api/users", tags=["users"])


@router.get("")
async def list_users():
    """Called by UserList.tsx."""
    return [{"id": "1", "name": "Amina", "email": "amina@example.com"}]


@router.get("/{user_id}")
async def get_user(user_id: str):
    """Called by UserDetail in UserList.tsx."""
    return {"id": user_id, "name": "Amina", "email": "amina@example.com"}


@router.delete("/{user_id}")
async def delete_user(user_id: str):
    """GHOST ROUTE: no screen in this app ever calls DELETE on a user."""
    return {"deleted": user_id}
