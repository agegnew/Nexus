import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { swarmRunner } from './scripts/swarm-dev.mjs'

// https://vite.dev/config/
// swarmRunner starts the Swarm tab's runner alongside `npm run dev`; builds are unaffected.
export default defineConfig({
  plugins: [react(), swarmRunner()],
})
