import { useEffect, useState } from 'react'
import { http, type Order } from './api'

/** The order screen. Both of its calls resolve to real backend routes. */
export function OrderPage({ userId }: { userId: string }) {
  const [orders, setOrders] = useState<Order[]>([])

  useEffect(() => {
    http<Order[]>('/api/orders').then(setOrders)
  }, [])

  async function placeOrder(total: number) {
    await fetch('/api/orders', {
      method: 'POST',
      body: JSON.stringify({ userId, total }),
    })
  }

  return (
    <section>
      <h2>Orders ({orders.length})</h2>
      <button onClick={() => placeOrder(49)}>Place order</button>
    </section>
  )
}
