import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { swarmRunner } from './scripts/swarm-dev.mjs'

/*
 * The Reel and Deck views are pages the IntelliJ plugin serves, not React views, so in
 * dev they have to be proxied through to it. Without this they 404 here and work only
 * inside the IDE, which is the opposite of what the dev server is for: the whole point is
 * that someone doing UI work can see every view in a normal browser with hot reload.
 *
 * 5199 is the port the plugin asks for first (see ReelServer.PREFERRED_PORT). If another
 * IDE window already took it the plugin falls back to a spare one and these paths stop
 * resolving here; the panel says so rather than showing an empty frame.
 *
 * It is 5199 and not 5174 because 5174 is where the second Vite dev server on a machine
 * lands, and the swarm's own demo app pins it.
 */
const PLUGIN = 'http://127.0.0.1:5199'

// https://vite.dev/config/
// swarmRunner starts the Swarm tab's runner alongside `npm run dev`; builds are unaffected.
export default defineConfig({
  plugins: [react(), swarmRunner()],
  server: {
    proxy: {
      '/reel': PLUGIN,
      '/deck': PLUGIN,
      '/shared': PLUGIN,
      '/trust': PLUGIN,
      '/trust-api': PLUGIN,
      '/nexus.json': PLUGIN,
    },
  },
})
