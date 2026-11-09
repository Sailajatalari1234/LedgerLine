import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Listen on the IPv4 address so http://127.0.0.1:5173 works in the browser.
    host: '127.0.0.1',
    port: 5173,
  },
})