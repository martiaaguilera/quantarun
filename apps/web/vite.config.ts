import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// In development the Vite server proxies API calls so the browser sees one origin, the same as behind nginx in
// docker compose. That keeps CORS out of the control plane entirely.
const controlPlane = process.env['QUANTARUN_CONTROL_PLANE_URL'] ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': controlPlane,
      '/actuator': controlPlane,
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
})
