// Date ranges for the Activity tab. Kept out of the view so the module only exports components.

const iso = (date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 10)

/** Weeks start on Monday, which is how most people think about "last week". */
function startOfWeek(date) {
  const start = new Date(date)
  start.setDate(start.getDate() - ((start.getDay() + 6) % 7))
  return start
}

function shift(date, days) {
  const next = new Date(date)
  next.setDate(next.getDate() + days)
  return next
}

export const RANGES = [
  { id: 'this-week', label: 'This week', of: (today) => [startOfWeek(today), today] },
  {
    id: 'last-week',
    label: 'Last week',
    of: (today) => [shift(startOfWeek(today), -7), shift(startOfWeek(today), -1)]
  },
  { id: 'this-month', label: 'This month', of: (today) => [new Date(today.getFullYear(), today.getMonth(), 1), today] },
  {
    id: 'last-month',
    label: 'Last month',
    of: (today) => [
      new Date(today.getFullYear(), today.getMonth() - 1, 1),
      new Date(today.getFullYear(), today.getMonth(), 0)
    ]
  },
  { id: 'last-30', label: 'Last 30 days', of: (today) => [shift(today, -29), today] },
  { id: 'custom', label: 'Custom range', of: null }
]

/** The two ISO dates a preset covers, or null for the custom range. */
export function rangeDates(id, today = new Date()) {
  const preset = RANGES.find((range) => range.id === id)
  if (!preset?.of) return null
  const [since, until] = preset.of(today)
  return { since: iso(since), until: iso(until) }
}
