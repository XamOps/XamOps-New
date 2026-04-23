import { defineConfig, createLogger } from 'vite';
import react from '@vitejs/plugin-react';
// Suppress noisy "Failed to load source map" warnings from third-party packages
// that reference .map files they don't actually ship (e.g. react-quill-new).
const logger = createLogger();
const originalWarn = logger.warn.bind(logger);
logger.warn = (msg, options) => {
  if (msg.includes('Failed to load source map')) return;
  originalWarn(msg, options);
};

// --- CONFIGURATION ---
// Define the default auth port and target
const authPort = process.env.AUTH_PORT || '8080';
const authTarget = `http://localhost:${authPort}`;

// --- HELPER FUNCTION ---
// This function rewrites the backend's cookie to work with the frontend proxy
const cookieRewrite = (proxyRes: any, _req: any) => {
  const cookies = proxyRes.headers['set-cookie'];
  if (cookies) {
    console.log('🍪 Original cookies from backend:', cookies);
    const newCookies = cookies.map((cookie: string) => {
      // Remove domain restrictions and ensure path is root
      let rewrittenCookie = cookie
        .replace(/;\s*path=[^;]*/gi, '') // Remove existing path
        .replace(/;\s*domain=[^;]*/gi, ''); // Remove existing domain

      // Add path=/ at the end
      rewrittenCookie += '; path=/';

      return rewrittenCookie;
    });
    console.log('🍪 Rewritten cookies for frontend:', newCookies);
    proxyRes.headers['set-cookie'] = newCookies;
  }
};

export default defineConfig({
  customLogger: logger,

  plugins: [
    react(),
  ],

  css: {
    devSourcemap: false,
  },

  // Define global for browser compatibility (fixes sockjs-client)
  define: {
    global: 'globalThis',
    'process.env': {},
  },

  optimizeDeps: {
    esbuildOptions: {
      define: {
        global: 'globalThis',
      },
    },
  },

  server: {
    port: 5173,
    proxy: {
      // --- 0. WEBSOCKET PROXY (Must be first) ---
      '/xamops-ws': {
        target: 'http://localhost:8080', // Support SockJS (http + ws upgrade)
        ws: true,
        changeOrigin: true,
        secure: false,
        configure: (proxy: any) => {
          proxy.on('error', (err: any) => {
            // EPIPE/ECONNRESET are normal when clients disconnect or backend restarts
            if (err.code !== 'EPIPE' && err.code !== 'ECONNRESET') {
              console.error('[xamops-ws proxy error]', err.message);
            }
          });
        },
      },
      // Terminal WebSocket
      '/terminal': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true,
        secure: false,
        configure: (proxy: any) => {
          proxy.on('error', (err: any) => {
            if (err.code !== 'EPIPE' && err.code !== 'ECONNRESET') {
              console.error('[terminal proxy error]', err.message);
            }
          });
        },
      },
      // --- 1. CORE AUTHENTICATION ---
      '/login': {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        configure: (proxy: any) => {
          proxy.on('proxyRes', cookieRewrite);
        }
      },
      '/logout': {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        configure: (proxy: any) => {
          proxy.on('proxyRes', cookieRewrite);
        }
      },
      // ✅ ADDED: Bridge for Password Reset Feature
      '/api/auth': {
        target: authTarget,
        changeOrigin: true,
        secure: false,
      },

      // --- 2. USER PROFILE (Fixes Sidebar Visibility) ---
      // Intercepts XamOps profile call and rewrites it to BillOps format if needed
      '/api/xamops/user/profile': {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        rewrite: (path: string) => {
          if (authPort === '8082') {
            return path.replace('/api/xamops/user/profile', '/api/billops/profile');
          }
          return path; // Keep as-is for XamOps
        }
      },

      // --- 3. SHARED MODULES ---
      // Routes Account Manager requests to the active backend
      '/api/xamops/account-manager': {
        target: authTarget,
        changeOrigin: true,
        secure: false
      },
      // Routes Dashboard Data requests to the active backend (Fixes ECONNREFUSED)
      '/api/xamops/dashboard': {
        target: authTarget,
        changeOrigin: true,
        secure: false
      },

      // --- 4. CLOUD PROVIDER APIs ---
      // These fallbacks point to 'authTarget' to handle cases where code resides in different services
      '/api/azure': { target: authTarget, changeOrigin: true, secure: false },
      '/api/aws': { target: authTarget, changeOrigin: true, secure: false },
      '/api/gcp': { target: authTarget, changeOrigin: true, secure: false },

      // --- 5. SPECIFIC SERVICE ENDPOINTS ---
      // Admin & Billops API requests always go to 8082 (billops-service)
      '/api/admin': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        secure: false,
      },
      '/api/billops': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        secure: false,
      },
      // CloudList API (needs longer timeout for large datasets)
      '/api/xamops/cloudlist': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        timeout: 300000, // 300s timeout for cloudlist
        proxyTimeout: 300000 // 300s proxy timeout
      },
      // General XamOps requests (catch-all for other xamops endpoints)
      '/api/xamops': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        timeout: 300000, // 300s timeout
        proxyTimeout: 300000 // 300s proxy timeout
      },

      // Feature specific endpoints
      '/api/ai-advisor': { target: authTarget, changeOrigin: true, secure: false },
      '/api/cicd': { target: authTarget, changeOrigin: true, secure: false },
      '/api/karpenter': { target: authTarget, changeOrigin: true, secure: false },
      '/api/ai': { target: authTarget, changeOrigin: true, secure: false },
      '/api/marketops': { target: authTarget, changeOrigin: true, secure: false },
      '/api/isv': { target: authTarget, changeOrigin: true, secure: false },
      '/api/metering': { target: authTarget, changeOrigin: true, secure: false },
      '/api/workspace': { target: authTarget, changeOrigin: true, secure: false }, // Google Workspace integration
      '/api/dev_ops_script': { target: 'http://localhost:8080', changeOrigin: true, secure: false }, // DevOps In The Box scripts
      '/api/flexcompute': { target: 'http://localhost:8080', changeOrigin: true, secure: false }, // FlexCompute (CloudiDR)
      '/api/autospotting': { target: 'http://localhost:8080', changeOrigin: true, secure: false },
      '/api/rightsizing': { target: 'http://localhost:8080', changeOrigin: true, secure: false }, // K8s Right-Sizing
      '/api/ebs': { target: 'http://localhost:8080', changeOrigin: true, secure: false }, // EBS Disk Rightsizing
      '/api/dbops': { target: 'http://localhost:8080', changeOrigin: true, secure: false }, // DbOps
      '/api/observability': { target: 'http://localhost:8080', changeOrigin: true, secure: false }, // Observability
      '/api/v1': { target: 'http://localhost:8080', changeOrigin: true, secure: false }, // Agent endpoints
      '/api/sre': { target: 'http://localhost:8080', changeOrigin: true, secure: false }, // SRE Investigations


    }
  },

  build: {
    // For React SPA, Vite automatically uses index.html as entry point
    // No need to specify rollupOptions.input
  },
});
