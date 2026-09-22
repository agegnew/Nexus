from fastapi import APIRouter, HTTPException

router = APIRouter(prefix="/api/users", tags=["users"])

USERS = [
    {"id": 1, "name": "Ada Lovelace", "email": "ada@harbor.market", "city": "London", "orders": 12, "tier": "Gold"},
    {"id": 2, "name": "Alan Turing", "email": "alan@harbor.market", "city": "Manchester", "orders": 7, "tier": "Silver"},
    {"id": 3, "name": "Grace Hopper", "email": "grace@harbor.market", "city": "New York", "orders": 21, "tier": "Gold"},
    {"id": 4, "name": "Linus Torvalds", "email": "linus@harbor.market", "city": "Portland", "orders": 3, "tier": "Bronze"},
    {"id": 5, "name": "Margaret Hamilton", "email": "margaret@harbor.market", "city": "Boston", "orders": 9, "tier": "Silver"},
]


@router.get("")
def list_users():
    return USERS


@router.get("/{user_id}")
def get_user(user_id: int):
    for user in USERS:
        if user["id"] == user_id:
            return user
    raise HTTPException(status_code=404, detail="User not found")


# The web app has no delete button, so this is a ghost route.
@router.delete("/{user_id}")
def delete_user(user_id: int):
    USERS[:] = [user for user in USERS if user["id"] != user_id]
    return {"deleted": user_id}

# Note: there is deliberately no PUT /api/users/{user_id}/settings.
# The Settings page calls it anyway, which is the bug the swarm finds.
