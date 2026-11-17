import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  server: {
    // Same-origin calls in dev: /api/* goes to the Spring Boot app.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
