import { useEffect, useState } from 'react'
import { http, type User } from './api'

/** Lists every user. Wired to GET /api/users. */
export function UserList() {
  const [users, setUsers] = useState<User[]>([])

  useEffect(() => {
    http<User[]>('/api/users').then(setUsers)
  }, [])

  return (
    <ul>
      {users.map((user) => (
        <li key={user.id}>{user.name}</li>
      ))}
    </ul>
  )
}

/** Loads one user. Wired to GET /api/users/{user_id}. */
export function UserDetail({ userId }: { userId: string }) {
  const [user, setUser] = useState<User | null>(null)

  useEffect(() => {
    http<User>(`/api/users/${userId}`).then(setUser)
  }, [userId])

  return <h2>{user?.name ?? 'Loading...'}</h2>
}
