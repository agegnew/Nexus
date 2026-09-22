import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/*
 * The Reel and Deck views are pages the IntelliJ plugin serves, not React views, so in
 * dev they have to be proxied through to it. Without this they 404 here and work only
 * inside the IDE, which is the opposite of what the dev server is for: the whole point is
 * that someone doing UI work can see every view in a normal browser with hot reload.
 *
 * 5174 is the port the plugin asks for first (see ReelServer.PREFERRED_PORT). If another
 * IDE window already took it the plugin falls back to a spare one and these paths stop
 * resolving here; the panel says so rather than showing an empty frame.
 */
const PLUGIN = 'http://127.0.0.1:5174'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/reel': PLUGIN,
      '/deck': PLUGIN,
      '/shared': PLUGIN,
      '/nexus.json': PLUGIN,
    },
  },
})
