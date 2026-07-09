import { defineConfig } from 'vitest/config';

// base: './' — the bundle is served from Android AAR assets under an arbitrary
// path, so all emitted asset URLs must be relative.
export default defineConfig({
  base: './',
  build: {
    target: 'es2022',
    outDir: 'dist',
  },
  server: {
    port: 5173,
    proxy: {
      // Dev mode proxies the API (HTTP + WebSocket) to the mock server.
      '/api': {
        target: 'http://localhost:8394',
        changeOrigin: true,
        ws: true,
      },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
