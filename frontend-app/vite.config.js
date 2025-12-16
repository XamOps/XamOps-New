import { defineConfig } from 'vite';
import { resolve } from 'path';
import htmlInclude from 'vite-plugin-html-include';

// --- CONFIGURATION ---
// Define the default auth port and target
const authPort = process.env.AUTH_PORT || '8080';
const authTarget = `http://localhost:${authPort}`;

// --- HELPER FUNCTION ---
// This function rewrites the backend's cookie to work with the frontend proxy
const cookieRewrite = (proxyRes, req) => {
  const cookies = proxyRes.headers['set-cookie'];
  if (cookies) {
    const newCookies = cookies.map(cookie =>
      cookie
        .replace(/; path=\/.*?(;|$)/, '; path=/;') // Set Path to root
        .replace(/; domain=.*?(;|$)/, `; domain=${req.headers.host.split(':')[0]};`) // Set Domain to frontend
    );
    proxyRes.headers['set-cookie'] = newCookies;
  }
};



export default defineConfig({
  plugins: [
    htmlInclude(),
  ],

  server: {
    port: 5173,
    proxy: {
      // --- 1. CORE AUTHENTICATION ---
      '/login': {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          proxy.on('proxyRes', cookieRewrite);
        }
      },
      '/logout': {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          proxy.on('proxyRes', cookieRewrite);
        }
      },

      // --- 2. USER PROFILE (Fixes Sidebar Visibility) ---
      // Intercepts XamOps profile call and rewrites it to BillOps format if needed
      '/api/xamops/user/profile': {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        rewrite: (path) => {
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
      // General XamOps requests (catch-all for other xamops endpoints)
      '/api/xamops': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false
      },

      // Feature specific endpoints
      '/api/ai-advisor': { target: authTarget, changeOrigin: true, secure: false },
      '/api/cicd': { target: authTarget, changeOrigin: true, secure: false },

      // --- 6. WEBSOCKETS ---
      // Explicit proxy for the renamed /terminal endpoint
      '/terminal': {
        target: authTarget.replace('http', 'ws'), // Proxies to ws://localhost:8080/terminal
        ws: true,
        changeOrigin: true,
        secure: false
      },
      // General WebSocket proxy
      '/ws': {
        target: authTarget.replace('http', 'ws'),
        ws: true,
        changeOrigin: true,
        followRedirects: false,
        rewriteWsOrigin: true,
        timeout: 10000,
      },
    }
  },

  build: {
    rollupOptions: {
      input: {
        index: resolve(__dirname, 'index.html'),
        'account-manager': resolve(__dirname, 'account-manager.html'),
        'user-manager': resolve(__dirname, 'user-manager.html'),
        'Xamops_User_management.html': resolve(__dirname, 'Xamops_User_management.html'),
        'add-account': resolve(__dirname, 'add-account.html'),
        'add-gcp-account': resolve(__dirname, 'add-gcp-account.html'),
        alerts: resolve(__dirname, 'alerts.html'),
        cloudk8s: resolve(__dirname, 'cloudk8s.html'),
        cloudlist: resolve(__dirname, 'cloudlist.html'),
        cloudmap: resolve(__dirname, 'cloudmap.html'),
        cost: resolve(__dirname, 'cost.html'),
        dashboard: resolve(__dirname, 'dashboard.html'),
        'eks-details': resolve(__dirname, 'eks-details.html'),
        finops: resolve(__dirname, 'finops.html'),
        rightsizing: resolve(__dirname, 'rightsizing.html'),
        reservation: resolve(__dirname, 'reservation.html'),
        waste: resolve(__dirname, 'waste.html'),
        performance: resolve(__dirname, 'performance.html'),
        xamops_tickets: resolve(__dirname, 'xamops_tickets.html'),
        gcp_xamops_tickets: resolve(__dirname, 'gcp_xamops_tickets.html'),
        xamops_ticket_detail: resolve(__dirname, 'xamops_ticket_detail.html'),
        gcp_xamops_ticket_detail: resolve(__dirname, 'gcp_xamops_ticket_detail.html'),
        'grafana-dashboard.html': resolve(__dirname, 'grafana-dashboard.html'),
        'devops_in_the_box.html': resolve(__dirname, 'devops_in_the_box.html'),
        'gcp_devops_in_the_box.html': resolve(__dirname, 'gcp_devops_in_the_box.html'),
        'cicd_pipelines.html': resolve(__dirname, 'cicd_pipelines.html'),
        security: resolve(__dirname, 'security.html'),
        'add-github-config.html': resolve(__dirname, 'add-github-config.html'),
        'sonarqube.html': resolve(__dirname, 'sonarqube.html'),
        'aiops.html': resolve(__dirname, 'aiops.html'),
        'complianceops.html': resolve(__dirname, 'complianceops.html'),
        'dataops.html': resolve(__dirname, 'dataops.html'),
        'cloudshell.html': resolve(__dirname, 'cloudshell.html'),
        'spot-automation.html': resolve(__dirname, 'spot-automation.html'),
        'actions-history.html': resolve(__dirname, 'actions-history.html'),
        'launch-analytics.html': resolve(__dirname, 'launch-analytics.html'),
        'risp-coverage.html': resolve(__dirname, 'risp-coverage.html'),
        // Admin subdir files
        'admin_credits': resolve(__dirname, 'billops/admin_credits.html'),
        'admin_invoice_detail': resolve(__dirname, 'billops/admin_invoice_detail.html'),
        'admin_invoices': resolve(__dirname, 'billops/admin_invoices.html'),
        'admin_tickets': resolve(__dirname, 'billops/admin_tickets.html'),
        'billing': resolve(__dirname, 'billops/billing.html'),
        'tickets': resolve(__dirname, 'billops/tickets.html'),
        'ticket_detail': resolve(__dirname, 'billops/ticket_detail.html'),
        'credits': resolve(__dirname, 'billops/credits.html'),
        'invoices': resolve(__dirname, 'billops/invoices.html'),
        'admin_cloudfront_billing': resolve(__dirname, 'billops/admin_cloudfront_billing.html'),
        'marketplace-purchases': resolve(__dirname, 'billops/marketplace-purchases.html'),
        'thirdparty-tools': resolve(__dirname, 'billops/thirdparty-tools.html'),
        'workspace-licenses': resolve(__dirname, 'billops/workspace-licenses.html'),

        // GCP subdir files
        'gcp_cloudlist': resolve(__dirname, 'gcp_cloudlist.html'),
        'gcp_cloudmap': resolve(__dirname, 'gcp_cloudmap.html'),
        'gcp_cost': resolve(__dirname, 'gcp_cost.html'),
        'gcp_dashboard': resolve(__dirname, 'gcp_dashboard.html'),
        'gcp_finops': resolve(__dirname, 'gcp_finops.html'),
        'gcp-billing': resolve(__dirname, 'billops/gcp-billing.html'),
        'gcp_rightsizing': resolve(__dirname, 'gcp_rightsizing.html'),
        'gcp_security': resolve(__dirname, 'gcp_security.html'),
        'gcp_waste': resolve(__dirname, 'gcp_waste.html'),
        'gcp_cloudk8s.html': resolve(__dirname, 'gcp_cloudk8s.html'),
        'gcp_performance.html': resolve(__dirname, 'gcp_performance.html'),
        'gcp_reservations.html': resolve(__dirname, 'gcp_reservations.html'),
        'gcp_alerts.html': resolve(__dirname, 'gcp_alerts.html'),

        // Azure files
        'azure_cloudlist': resolve(__dirname, 'azure_cloudlist.html'),
        'azure_dashboard': resolve(__dirname, 'azure_dashboard.html'),
        'azure-finops-report': resolve(__dirname, 'azure-finops-report.html')
      },
    },
  },
});