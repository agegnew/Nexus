import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 5174 because the Nexus map already owns 5173. /api goes to the FastAPI backend.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:8000' },
  },
})
