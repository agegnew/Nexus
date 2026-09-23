// One typed wrapper around fetch. It trusts the server, which is exactly the kind of
// shortcut a real codebase takes: an error body comes back looking like data.
export async function http<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers ?? {}) },
  })
  return response.json() as Promise<T>
}

export type User = { id: number; name: string; email: string; city: string; orders: number; tier: string }
export type Order = { id: number; item: string; quantity: number; total: number }
export type OrderBook = { menu: Record<string, number>; orders: Order[] }
export type Summary = { revenue: number; orders: number; topItems: { item: string; count: number }[] }
