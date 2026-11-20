import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  // KIRIM_API_URL lets you point the dev proxy elsewhere when 8080 is taken.
  const env = loadEnv(mode, '.', '')
  return {
    plugins: [react()],
    server: {
      // Same-origin calls in dev: /api/* goes to the Spring Boot app.
      proxy: {
        '/api': env.KIRIM_API_URL ?? 'http://localhost:8080',
      },
    },
  }
})