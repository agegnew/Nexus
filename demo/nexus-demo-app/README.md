# Nexus Demo App

A deliberately small full-stack app used to demonstrate the Nexus plugin.

Open **this folder** as a project in the sandbox IDE, then open
**View → Tool Windows → Nexus**. Analysis finishes in seconds because there is
no `node_modules` here.

## What Nexus should find

| Metric | Expected |
|---|---|
| Frontend API calls | **6** |
| Backend endpoints | **6** |
| Matched (wired) calls | **4** |
| Broken wires (frontend call with no backend) | **2** |
| Ghost routes (backend nobody calls) | **2** |

## The wiring

| Frontend | Call | Backend |
|---|---|---|
| `UserList.tsx` | `GET /api/users` | `users.list_users()` |
| `UserList.tsx` | `GET /api/users/{id}` | `users.get_user()` |
| `OrderPage.tsx` | `GET /api/orders` | `orders.list_orders()` |
| `OrderPage.tsx` | `POST /api/orders` | `orders.create_order()` |
| `Settings.tsx` | `PUT /api/users/{id}/settings` | **nothing — broken wire** |
| `Analytics.tsx` | `GET /api/analytics/summary` | **nothing — broken wire** |

Ghost routes (exist in the backend, no screen calls them):

- `DELETE /api/users/{user_id}` in `backend/app/users.py`
- `GET /health` in `backend/app/main.py`

## Why the broken ones are there

They are the point of the demo. Nexus never invents a relationship: a call it
cannot resolve is shown as unresolved rather than quietly hidden, and an
endpoint nobody calls is shown as dead code. Both are real bugs this kind of
map is supposed to surface.

## Running it for real (optional)

The demo does not need to run — Nexus reads the source, it does not execute it.
If you want a live server anyway:

```bash
cd backend
pip install -r requirements.txt
uvicorn app.main:app --reload
```
