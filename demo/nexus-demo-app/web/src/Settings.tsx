import { useState } from 'react'

/**
 * Saves a user's settings.
 *
 * BROKEN ON PURPOSE: the backend never implemented
 * PUT /api/users/{user_id}/settings, so this call has nowhere to land.
 * Nexus shows it as an unresolved request instead of pretending it works.
 */
export function Settings({ userId }: { userId: string }) {
  const [theme, setTheme] = useState('dark')

  async function save() {
    await fetch(`/api/users/${userId}/settings`, {
      method: 'PUT',
      body: JSON.stringify({ theme }),
    })
  }

  return (
    <form onSubmit={save}>
      <select value={theme} onChange={(event) => setTheme(event.target.value)}>
        <option value="dark">Dark</option>
        <option value="light">Light</option>
      </select>
      <button type="submit">Save</button>
    </form>
  )
}
