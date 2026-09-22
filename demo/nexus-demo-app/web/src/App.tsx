import { useState } from 'react'
import UserList from './UserList'
import OrderPage from './OrderPage'
import Settings from './Settings'
import Analytics from './Analytics'

const PAGES = ['Customers', 'Order', 'Settings', 'Analytics'] as const
type Page = (typeof PAGES)[number]

export default function App() {
  const [page, setPage] = useState<Page>('Customers')

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand"><span className="brand__mark">⚓</span> Harbor Market</div>
        <nav aria-label="Main">
          {PAGES.map((name) => (
            <button
              key={name}
              type="button"
              className={page === name ? 'nav nav--active' : 'nav'}
              aria-current={page === name ? 'page' : undefined}
              onClick={() => setPage(name)}
            >
              {name}
            </button>
          ))}
        </nav>
      </header>
      <main className="content">
        {page === 'Customers' && <UserList />}
        {page === 'Order' && <OrderPage />}
        {page === 'Settings' && <Settings />}
        {page === 'Analytics' && <Analytics />}
      </main>
    </div>
  )
}
