import { useEffect, useState } from 'react'
import { http, type Summary } from './api'

export default function Analytics() {
  const [summary, setSummary] = useState<Summary | null>(null)

  useEffect(() => {
    http<Summary>('/api/analytics/summary').then(setSummary)
  }, [])

  if (!summary) return <section className="page"><h1>Analytics</h1><p className="lede">Loading…</p></section>

  return (
    <section className="page">
      <h1>Analytics</h1>
      <div className="stats">
        <div className="card"><span>Revenue</span><strong>£{summary.revenue.toFixed(2)}</strong></div>
        <div className="card"><span>Orders</span><strong>{summary.orders}</strong></div>
      </div>
      <ul className="list">
        {summary.topItems.map((top) => <li key={top.item}>{top.item} · {top.count}</li>)}
      </ul>
    </section>
  )
}
