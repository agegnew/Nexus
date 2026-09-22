import { useState } from 'react'
import { http } from './api'

const userId = 3

export default function Settings() {
  const [newsletter, setNewsletter] = useState(true)
  const [nickname, setNickname] = useState('Grace')
  const [status, setStatus] = useState('')

  async function save() {
    setStatus('Saving…')
    const result = await http<{ saved?: boolean }>(`/api/users/${userId}/settings`, {
      method: 'PUT',
      body: JSON.stringify({ newsletter, nickname }),
    })
    if (result.saved) setStatus('Settings saved')
  }

  return (
    <section className="page">
      <h1>Settings</h1>
      <p className="lede">How the harbor should talk to you.</p>
      <div className="card form">
        <label>
          Nickname
          <input value={nickname} onChange={(event) => setNickname(event.target.value)} />
        </label>
        <label className="check">
          <input type="checkbox" checked={newsletter} onChange={(event) => setNewsletter(event.target.checked)} />
          Send me the weekly newsletter
        </label>
        <button type="button" className="primary" onClick={save}>Save settings</button>
        {status && <p className="status" role="status">{status}</p>}
      </div>
    </section>
  )
}
