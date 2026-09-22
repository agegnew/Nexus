import { useEffect, useState } from 'react'
import { http, type User } from './api'

export default function UserList() {
  const [users, setUsers] = useState<User[]>([])
  const [selected, setSelected] = useState<User | null>(null)

  useEffect(() => {
    http<User[]>('/api/users').then(setUsers)
  }, [])

  async function open(user: User) {
    const detail = await http<User>(`/api/users/${user.id}`)
    setSelected(detail)
  }

  return (
    <section className="page">
      <h1>Customers</h1>
      <p className="lede">Everyone who ordered from the harbor this month.</p>
      <div className="split">
        <ul className="list" aria-label="Customers">
          {users.map((user) => (
            <li key={user.id}>
              <button type="button" className="row" onClick={() => open(user)}>
                <strong>{user.name}</strong>
                <span>{user.city}</span>
              </button>
            </li>
          ))}
        </ul>
        {selected ? (
          <article className="card" aria-label="Customer profile">
            <h2>{selected.name}</h2>
            <dl>
              <dt>Email</dt><dd>{selected.email}</dd>
              <dt>City</dt><dd>{selected.city}</dd>
              <dt>Orders</dt><dd>{selected.orders}</dd>
              <dt>Tier</dt><dd><span className="pill">{selected.tier}</span></dd>
            </dl>
          </article>
        ) : (
          <div className="card card--empty">Select a customer to see their profile.</div>
        )}
      </div>
    </section>
  )
}
