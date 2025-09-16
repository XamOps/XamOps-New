import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    port: 5173, // Your frontend port
    proxy: {
      // --- THIS IS THE NEW, CRUCIAL RULE ---
      // Proxy admin API requests to the billops-service
      '/api/admin': {
        target: 'http://localhost:8082', // Target the billops-service
        changeOrigin: true,
        secure: false,
      },

      // Proxy billops service requests
      '/api/billops': {
        target: 'http://localhost:8082', // Target the billops-service
        changeOrigin: true,
      },
      
      // Proxy xamops service requests
      '/api/xamops': {
        target: 'http://localhost:8080', // Target the xamops-service
        changeOrigin: true,
      },

      // Proxy authentication requests to xamops
      '/login': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/logout': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },

      // WebSocket proxy for xamops
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      }
    }
  }
});