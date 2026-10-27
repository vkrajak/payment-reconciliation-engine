/// <reference types="vitest" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// In dev, /api is proxied to dashboard-api so the browser never hits CORS.
// Override the target with DASHBOARD_API_URL if the API is not on localhost:8086.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: process.env.DASHBOARD_API_URL ?? 'http://localhost:8086',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
