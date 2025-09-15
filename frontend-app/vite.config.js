import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    proxy: {
      // Proxy xamops service requests
      '/api/xamops': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/xamops/, '/api/xamops'),
      },

      // Proxy billops service requests
      '/api/billing': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/billing/, '/api/billing'),
      },

      // ✅ Proxy billops API requests
      '/api/billops': {
        target: 'http://localhost:8082', // or the correct backend port
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/billops/, '/api/billops'),
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
