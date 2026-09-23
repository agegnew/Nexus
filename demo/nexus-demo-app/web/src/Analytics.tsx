import axios from 'axios'
import { useEffect, useState } from 'react'

/**
 * Dashboard tile.
 *
 * BROKEN ON PURPOSE: there is no analytics service in this project at all,
 * so GET /api/analytics/summary resolves to nothing.
 */
export function Analytics() {
  const [revenue, setRevenue] = useState(0)

  useEffect(() => {
    axios.get('/api/analytics/summary').then((response) => {
      setRevenue(response.data.revenue)
    })
  }, [])

  return <strong>Revenue: {revenue}</strong>
}
