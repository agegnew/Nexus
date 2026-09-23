// The test account the agents sign in with, and a fresh identity for sign-up forms.
//
// The model never sees a password. It types placeholders such as {{password}}, and only the
// browser, at the moment it fills the field, swaps in the real value. Anything shown or logged
// (thoughts, the action line, the replay file, the report) still holds the placeholder.

const TOKEN = /\{\{\s*(username|password|new_email|new_username|new_password|new_name)\s*\}\}/g

export function createAccount({ username = '', password = '' } = {}, seed = Date.now()) {
  const user = String(username ?? '').trim()
  const secret = String(password ?? '')
  const tag = String(seed).slice(-6)
  const values = {
    username: user,
    password: secret,
    new_email: `nexus.tester+${tag}@example.com`,
    new_username: `nexus_tester_${tag}`,
    new_password: `Nx!${tag}test${tag.split('').reverse().join('')}`,
    new_name: 'Nexus Tester',
  }

  return {
    hasLogin: Boolean(user && secret),
    username: user,

    /** Swaps placeholders for real values. Only the browser-facing code calls this. */
    resolve(text) {
      return String(text ?? '').replace(TOKEN, (_, key) => values[key] ?? '')
    },

    /** Hides a real password if the model somehow echoed it, and any placeholder for one. */
    mask(text) {
      let masked = String(text ?? '').replace(/\{\{\s*(password|new_password)\s*\}\}/g, '••••••')
      if (secret) masked = masked.split(secret).join('••••••')
      return masked
    },

    /** What the model is told about signing in. */
    brief() {
      const lines = []
      if (user && secret) {
        lines.push(`Test account: username/email "${user}". To type its password, fill the field with the exact text {{password}} (the browser swaps in the real one).`)
      } else if (user) {
        lines.push(`Test account: username/email "${user}" (no password was given).`)
      } else {
        lines.push('No test account was given.')
      }
      lines.push('If you need a NEW account (a sign-up form), use {{new_name}}, {{new_email}}, {{new_username}} and {{new_password}} for the fields.')
      return lines.join('\n')
    },
  }
}
