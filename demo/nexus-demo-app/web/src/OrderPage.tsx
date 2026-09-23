import { useEffect, useState } from 'react'
import { http, type Order, type OrderBook } from './api'

export default function OrderPage() {
  const [menu, setMenu] = useState<Record<string, number>>({})
  const [item, setItem] = useState('Flat white')
  const [quantity, setQuantity] = useState('1')
  const [placed, setPlaced] = useState<Order | null>(null)

  useEffect(() => {
    http<OrderBook>('/api/orders').then((book) => setMenu(book.menu))
  }, [])

  async function place(event: React.FormEvent) {
    event.preventDefault()
    const order = await http<Order>('/api/orders', {
      method: 'POST',
      body: JSON.stringify({ item, quantity: parseInt(quantity) }),
    })
    setPlaced(order)
  }

  return (
    <section className="page">
      <h1>Place an order</h1>
      <p className="lede">Fresh from the harbor kitchen.</p>
      <form className="card form" onSubmit={place}>
        <label>
          Item
          <select value={item} onChange={(event) => setItem(event.target.value)}>
            {Object.entries(menu).map(([name, price]) => (
              <option key={name} value={name}>{name} · £{price.toFixed(2)}</option>
            ))}
          </select>
        </label>
        <label>
          Quantity
          <input value={quantity} onChange={(event) => setQuantity(event.target.value)} />
        </label>
        <button type="submit" className="primary">Place order</button>
      </form>
      {placed && (
        <p className="toast" role="status">
          {`Order #${placed.id} placed: ${placed.quantity} × ${placed.item} (£${placed.total})`}
        </p>
      )}
    </section>
  )
}
