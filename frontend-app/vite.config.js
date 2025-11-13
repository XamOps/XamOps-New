import { defineConfig } from 'vite';
import { resolve } from 'path';
import htmlInclude from 'vite-plugin-html-include';

// --- ADD THIS HELPER FUNCTION ---
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
// --- END OF HELPER FUNCTION ---

export default defineConfig({
  plugins: [
    htmlInclude(),
  ],

  server: {
    port: 5173,
    proxy: {
      // Proxy admin API requests to the billops-service
      '/api/admin': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        secure: false,
      },
      '/api/ai-advisor': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
      // Proxy billops service requests
      '/api/billops': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        secure: false,
      },
      // Proxy xamops service requests
      '/api/xamops': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      },

      // --- ADD THE 'configure' BLOCK TO LOGIN ---
      '/login': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          proxy.on('proxyRes', cookieRewrite);
        }
      },
      // --- ADD THE 'configure' BLOCK TO LOGOUT ---
      '/logout': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          proxy.on('proxyRes', cookieRewrite);
        }
      },

      // WebSocket proxy for xamops
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      }
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
        'add-github-config.html': resolve(__dirname, 'add-github-config.html'),
        security: resolve(__dirname, 'security.html'),
        'admin_credits': resolve(__dirname, 'billops/admin_credits.html'),
        'admin_invoice_detail': resolve(__dirname, 'billops/admin_invoice_detail.html'),
        'admin_invoices': resolve(__dirname, 'billops/admin_invoices.html'),
        'admin_tickets': resolve(__dirname, 'billops/admin_tickets.html'),
        'billing': resolve(__dirname, 'billops/billing.html'),
        'tickets': resolve(__dirname, 'billops/tickets.html'),
        'ticket_detail': resolve(__dirname, 'billops/ticket_detail.html'),
        'credits': resolve(__dirname, 'billops/credits.html'),
        'invoices': resolve(__dirname, 'billops/invoices.html'),
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
        'gcp_alerts.html': resolve(__dirname, 'gcp_alerts.html')
      },
    },
  },
});