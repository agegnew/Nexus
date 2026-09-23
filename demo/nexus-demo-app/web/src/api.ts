// Tiny typed HTTP wrapper used by every screen in this demo app.
// Nexus detects calls made through this wrapper as well as raw fetch and axios.

export interface User {
  id: string
  name: string
  email: string
}

export interface Order {
  id: string
  userId: string
  total: number
}

export async function http<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })

  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`)
  }

  return (await response.json()) as T
}
