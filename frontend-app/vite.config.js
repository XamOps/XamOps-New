// File: frontend-app/vite.config.js
import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    proxy: {
      // Proxy any request that starts with /api to your backend
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // Proxy the /login request to your backend
      '/login': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // Proxy the /logout request to your backend
      '/logout': {
          target: 'http://localhost:8080',
          changeOrigin: true,
      },
      // --- ADD THIS BLOCK FOR WEBSOCKETS ---
      '/ws': {
        target: 'ws://localhost:8080', // Use 'ws://' for WebSockets
        ws: true, // This is the crucial option that enables WebSocket proxying
      }
    }
  }
});