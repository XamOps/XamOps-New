// vite.config.js
import { defineConfig } from "file:///C:/xamops-latest/XamOps-New/frontend-app/node_modules/vite/dist/node/index.js";
import { resolve } from "path";
import htmlInclude from "file:///C:/xamops-latest/XamOps-New/frontend-app/node_modules/vite-plugin-html-include/dist/index.js";
var __vite_injected_original_dirname = "C:\\xamops-latest\\XamOps-New\\frontend-app";
var authPort = process.env.AUTH_PORT || "8080";
var authTarget = `http://localhost:${authPort}`;
var cookieRewrite = (proxyRes, req) => {
  const cookies = proxyRes.headers["set-cookie"];
  if (cookies) {
    const newCookies = cookies.map(
      (cookie) => cookie.replace(/; path=\/.*?(;|$)/, "; path=/;").replace(/; domain=.*?(;|$)/, `; domain=${req.headers.host.split(":")[0]};`)
      // Set Domain to frontend
    );
    proxyRes.headers["set-cookie"] = newCookies;
  }
};
var vite_config_default = defineConfig({
  plugins: [
    htmlInclude()
  ],
  server: {
    port: 5173,
    proxy: {
      // --- 1. CORE AUTHENTICATION ---
      "/login": {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          proxy.on("proxyRes", cookieRewrite);
        }
      },
      "/logout": {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          proxy.on("proxyRes", cookieRewrite);
        }
      },
      // ✅ ADDED: Bridge for Password Reset Feature
      "/api/auth": {
        target: authTarget,
        changeOrigin: true,
        secure: false
      },
      // --- 2. USER PROFILE (Fixes Sidebar Visibility) ---
      // Intercepts XamOps profile call and rewrites it to BillOps format if needed
      "/api/xamops/user/profile": {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        rewrite: (path) => {
          if (authPort === "8082") {
            return path.replace("/api/xamops/user/profile", "/api/billops/profile");
          }
          return path;
        }
      },
      // --- 3. SHARED MODULES ---
      // Routes Account Manager requests to the active backend
      "/api/xamops/account-manager": {
        target: authTarget,
        changeOrigin: true,
        secure: false
      },
      // Routes Dashboard Data requests to the active backend (Fixes ECONNREFUSED)
      "/api/xamops/dashboard": {
        target: authTarget,
        changeOrigin: true,
        secure: false
      },
      // --- 4. CLOUD PROVIDER APIs ---
      // These fallbacks point to 'authTarget' to handle cases where code resides in different services
      "/api/azure": { target: authTarget, changeOrigin: true, secure: false },
      "/api/aws": { target: authTarget, changeOrigin: true, secure: false },
      "/api/gcp": { target: authTarget, changeOrigin: true, secure: false },
      // --- 5. SPECIFIC SERVICE ENDPOINTS ---
      // Admin & Billops API requests always go to 8082 (billops-service)
      "/api/admin": {
        target: "http://localhost:8082",
        changeOrigin: true,
        secure: false
      },
      "/api/billops": {
        target: "http://localhost:8082",
        changeOrigin: true,
        secure: false
      },
      // General XamOps requests (catch-all for other xamops endpoints)
      "/api/xamops": {
        target: "http://localhost:8080",
        changeOrigin: true,
        secure: false
      },
      // Feature specific endpoints
      "/api/ai-advisor": { target: authTarget, changeOrigin: true, secure: false },
      "/api/cicd": { target: authTarget, changeOrigin: true, secure: false },
      "/api/karpenter": { target: authTarget, changeOrigin: true, secure: false },
      // --- 6. WEBSOCKETS ---
      // Explicit proxy for the renamed /terminal endpoint
      "/terminal": {
        target: authTarget.replace("http", "ws"),
        // Proxies to ws://localhost:8080/terminal
        ws: true,
        changeOrigin: true,
        secure: false
      },
      // General WebSocket proxy
      "/ws": {
        target: authTarget.replace("http", "ws"),
        ws: true,
        changeOrigin: true,
        followRedirects: false,
        rewriteWsOrigin: true,
        timeout: 1e4
      }
    }
  },
  build: {
    rollupOptions: {
      input: {
        index: resolve(__vite_injected_original_dirname, "index.html"),
        "account-manager": resolve(__vite_injected_original_dirname, "account-manager.html"),
        "user-manager": resolve(__vite_injected_original_dirname, "user-manager.html"),
        "Xamops_User_management.html": resolve(__vite_injected_original_dirname, "Xamops_User_management.html"),
        "add-account": resolve(__vite_injected_original_dirname, "add-account.html"),
        "add-gcp-account": resolve(__vite_injected_original_dirname, "add-gcp-account.html"),
        alerts: resolve(__vite_injected_original_dirname, "alerts.html"),
        cloudk8s: resolve(__vite_injected_original_dirname, "cloudk8s.html"),
        cloudlist: resolve(__vite_injected_original_dirname, "cloudlist.html"),
        cloudmap: resolve(__vite_injected_original_dirname, "cloudmap.html"),
        cost: resolve(__vite_injected_original_dirname, "cost.html"),
        dashboard: resolve(__vite_injected_original_dirname, "dashboard.html"),
        "eks-details": resolve(__vite_injected_original_dirname, "eks-details.html"),
        finops: resolve(__vite_injected_original_dirname, "finops.html"),
        rightsizing: resolve(__vite_injected_original_dirname, "rightsizing.html"),
        reservation: resolve(__vite_injected_original_dirname, "reservation.html"),
        waste: resolve(__vite_injected_original_dirname, "waste.html"),
        performance: resolve(__vite_injected_original_dirname, "performance.html"),
        xamops_tickets: resolve(__vite_injected_original_dirname, "xamops_tickets.html"),
        gcp_xamops_tickets: resolve(__vite_injected_original_dirname, "gcp_xamops_tickets.html"),
        xamops_ticket_detail: resolve(__vite_injected_original_dirname, "xamops_ticket_detail.html"),
        gcp_xamops_ticket_detail: resolve(__vite_injected_original_dirname, "gcp_xamops_ticket_detail.html"),
        "grafana-dashboard.html": resolve(__vite_injected_original_dirname, "grafana-dashboard.html"),
        "devops_in_the_box.html": resolve(__vite_injected_original_dirname, "devops_in_the_box.html"),
        "gcp_devops_in_the_box.html": resolve(__vite_injected_original_dirname, "gcp_devops_in_the_box.html"),
        "cicd_pipelines.html": resolve(__vite_injected_original_dirname, "cicd_pipelines.html"),
        security: resolve(__vite_injected_original_dirname, "security.html"),
        "add-github-config.html": resolve(__vite_injected_original_dirname, "add-github-config.html"),
        "sonarqube.html": resolve(__vite_injected_original_dirname, "sonarqube.html"),
        "aiops.html": resolve(__vite_injected_original_dirname, "aiops.html"),
        "complianceops.html": resolve(__vite_injected_original_dirname, "complianceops.html"),
        "dataops.html": resolve(__vite_injected_original_dirname, "dataops.html"),
        "cloudshell.html": resolve(__vite_injected_original_dirname, "cloudshell.html"),
        "spot-automation.html": resolve(__vite_injected_original_dirname, "spot-automation.html"),
        "actions-history.html": resolve(__vite_injected_original_dirname, "actions-history.html"),
        "launch-analytics.html": resolve(__vite_injected_original_dirname, "launch-analytics.html"),
        "risp-coverage.html": resolve(__vite_injected_original_dirname, "risp-coverage.html"),
        // Admin subdir files
        "admin_credits": resolve(__vite_injected_original_dirname, "billops/admin_credits.html"),
        "admin_invoice_detail": resolve(__vite_injected_original_dirname, "billops/admin_invoice_detail.html"),
        "admin_invoices": resolve(__vite_injected_original_dirname, "billops/admin_invoices.html"),
        "admin_tickets": resolve(__vite_injected_original_dirname, "billops/admin_tickets.html"),
        "billing": resolve(__vite_injected_original_dirname, "billops/billing.html"),
        "tickets": resolve(__vite_injected_original_dirname, "billops/tickets.html"),
        "admin_agreements": resolve(__vite_injected_original_dirname, "billops/admin_agreements.html"),
        "agreements": resolve(__vite_injected_original_dirname, "billops/agreements.html"),
        "ticket_detail": resolve(__vite_injected_original_dirname, "billops/ticket_detail.html"),
        "credits": resolve(__vite_injected_original_dirname, "billops/credits.html"),
        "invoices": resolve(__vite_injected_original_dirname, "billops/invoices.html"),
        "dashboard_admin": resolve(__vite_injected_original_dirname, "billops/dashboard_admin.html"),
        "dashboard_user": resolve(__vite_injected_original_dirname, "billops/dashboard_user.html"),
        "admin_dashboard.html": resolve(__vite_injected_original_dirname, "billops/admin_dashboard.html"),
        "admin_cloudfront_billing": resolve(__vite_injected_original_dirname, "billops/admin_cloudfront_billing.html"),
        "marketplace-purchases": resolve(__vite_injected_original_dirname, "billops/marketplace-purchases.html"),
        "thirdparty-tools": resolve(__vite_injected_original_dirname, "billops/thirdparty-tools.html"),
        "workspace-licenses": resolve(__vite_injected_original_dirname, "billops/workspace-licenses.html"),
        "unified-dashboard": resolve(__vite_injected_original_dirname, "unified-dashboard.html"),
        "cloudsitter.html": resolve(__vite_injected_original_dirname, "cloudsitter.html"),
        "calculator.html": resolve(__vite_injected_original_dirname, "calculator.html"),
        // GCP subdir files
        "gcp_cloudlist": resolve(__vite_injected_original_dirname, "gcp_cloudlist.html"),
        "gcp_cloudmap": resolve(__vite_injected_original_dirname, "gcp_cloudmap.html"),
        "gcp_cost": resolve(__vite_injected_original_dirname, "gcp_cost.html"),
        "gcp_dashboard": resolve(__vite_injected_original_dirname, "gcp_dashboard.html"),
        "gcp_finops": resolve(__vite_injected_original_dirname, "gcp_finops.html"),
        "gcp-billing": resolve(__vite_injected_original_dirname, "billops/gcp-billing.html"),
        "gcp_rightsizing": resolve(__vite_injected_original_dirname, "gcp_rightsizing.html"),
        "gcp_security": resolve(__vite_injected_original_dirname, "gcp_security.html"),
        "gcp_waste": resolve(__vite_injected_original_dirname, "gcp_waste.html"),
        "gcp_cloudk8s.html": resolve(__vite_injected_original_dirname, "gcp_cloudk8s.html"),
        "gcp_performance.html": resolve(__vite_injected_original_dirname, "gcp_performance.html"),
        "gcp_reservations.html": resolve(__vite_injected_original_dirname, "gcp_reservations.html"),
        "gcp_alerts.html": resolve(__vite_injected_original_dirname, "gcp_alerts.html"),
        // Azure files
        "azure_cloudlist": resolve(__vite_injected_original_dirname, "azure_cloudlist.html"),
        "azure_dashboard": resolve(__vite_injected_original_dirname, "azure_dashboard.html"),
        "azure-finops-report": resolve(__vite_injected_original_dirname, "azure-finops-report.html"),
        "superadmin_customer.html": resolve(__vite_injected_original_dirname, "superadmin_customer.html")
      }
    }
  }
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcuanMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCJDOlxcXFx4YW1vcHMtbGF0ZXN0XFxcXFhhbU9wcy1OZXdcXFxcZnJvbnRlbmQtYXBwXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ZpbGVuYW1lID0gXCJDOlxcXFx4YW1vcHMtbGF0ZXN0XFxcXFhhbU9wcy1OZXdcXFxcZnJvbnRlbmQtYXBwXFxcXHZpdGUuY29uZmlnLmpzXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ltcG9ydF9tZXRhX3VybCA9IFwiZmlsZTovLy9DOi94YW1vcHMtbGF0ZXN0L1hhbU9wcy1OZXcvZnJvbnRlbmQtYXBwL3ZpdGUuY29uZmlnLmpzXCI7aW1wb3J0IHsgZGVmaW5lQ29uZmlnIH0gZnJvbSAndml0ZSc7XHJcbmltcG9ydCB7IHJlc29sdmUgfSBmcm9tICdwYXRoJztcclxuaW1wb3J0IGh0bWxJbmNsdWRlIGZyb20gJ3ZpdGUtcGx1Z2luLWh0bWwtaW5jbHVkZSc7XHJcblxyXG4vLyAtLS0gQ09ORklHVVJBVElPTiAtLS1cclxuLy8gRGVmaW5lIHRoZSBkZWZhdWx0IGF1dGggcG9ydCBhbmQgdGFyZ2V0XHJcbmNvbnN0IGF1dGhQb3J0ID0gcHJvY2Vzcy5lbnYuQVVUSF9QT1JUIHx8ICc4MDgwJztcclxuY29uc3QgYXV0aFRhcmdldCA9IGBodHRwOi8vbG9jYWxob3N0OiR7YXV0aFBvcnR9YDtcclxuXHJcbi8vIC0tLSBIRUxQRVIgRlVOQ1RJT04gLS0tXHJcbi8vIFRoaXMgZnVuY3Rpb24gcmV3cml0ZXMgdGhlIGJhY2tlbmQncyBjb29raWUgdG8gd29yayB3aXRoIHRoZSBmcm9udGVuZCBwcm94eVxyXG5jb25zdCBjb29raWVSZXdyaXRlID0gKHByb3h5UmVzLCByZXEpID0+IHtcclxuICBjb25zdCBjb29raWVzID0gcHJveHlSZXMuaGVhZGVyc1snc2V0LWNvb2tpZSddO1xyXG4gIGlmIChjb29raWVzKSB7XHJcbiAgICBjb25zdCBuZXdDb29raWVzID0gY29va2llcy5tYXAoY29va2llID0+XHJcbiAgICAgIGNvb2tpZVxyXG4gICAgICAgIC5yZXBsYWNlKC87IHBhdGg9XFwvLio/KDt8JCkvLCAnOyBwYXRoPS87JykgLy8gU2V0IFBhdGggdG8gcm9vdFxyXG4gICAgICAgIC5yZXBsYWNlKC87IGRvbWFpbj0uKj8oO3wkKS8sIGA7IGRvbWFpbj0ke3JlcS5oZWFkZXJzLmhvc3Quc3BsaXQoJzonKVswXX07YCkgLy8gU2V0IERvbWFpbiB0byBmcm9udGVuZFxyXG4gICAgKTtcclxuICAgIHByb3h5UmVzLmhlYWRlcnNbJ3NldC1jb29raWUnXSA9IG5ld0Nvb2tpZXM7XHJcbiAgfVxyXG59O1xyXG5cclxuZXhwb3J0IGRlZmF1bHQgZGVmaW5lQ29uZmlnKHtcclxuICBwbHVnaW5zOiBbXHJcbiAgICBodG1sSW5jbHVkZSgpLFxyXG4gIF0sXHJcblxyXG4gIHNlcnZlcjoge1xyXG4gICAgcG9ydDogNTE3MyxcclxuICAgIHByb3h5OiB7XHJcbiAgICAgIC8vIC0tLSAxLiBDT1JFIEFVVEhFTlRJQ0FUSU9OIC0tLVxyXG4gICAgICAnL2xvZ2luJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldCxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcclxuICAgICAgICBjb25maWd1cmU6IChwcm94eSwgb3B0aW9ucykgPT4ge1xyXG4gICAgICAgICAgcHJveHkub24oJ3Byb3h5UmVzJywgY29va2llUmV3cml0ZSk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9LFxyXG4gICAgICAnL2xvZ291dCc6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXHJcbiAgICAgICAgY29uZmlndXJlOiAocHJveHksIG9wdGlvbnMpID0+IHtcclxuICAgICAgICAgIHByb3h5Lm9uKCdwcm94eVJlcycsIGNvb2tpZVJld3JpdGUpO1xyXG4gICAgICAgIH1cclxuICAgICAgfSxcclxuICAgICAgLy8gXHUyNzA1IEFEREVEOiBCcmlkZ2UgZm9yIFBhc3N3b3JkIFJlc2V0IEZlYXR1cmVcclxuICAgICAgJy9hcGkvYXV0aCc6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXHJcbiAgICAgIH0sXHJcblxyXG4gICAgICAvLyAtLS0gMi4gVVNFUiBQUk9GSUxFIChGaXhlcyBTaWRlYmFyIFZpc2liaWxpdHkpIC0tLVxyXG4gICAgICAvLyBJbnRlcmNlcHRzIFhhbU9wcyBwcm9maWxlIGNhbGwgYW5kIHJld3JpdGVzIGl0IHRvIEJpbGxPcHMgZm9ybWF0IGlmIG5lZWRlZFxyXG4gICAgICAnL2FwaS94YW1vcHMvdXNlci9wcm9maWxlJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldCxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcclxuICAgICAgICByZXdyaXRlOiAocGF0aCkgPT4ge1xyXG4gICAgICAgICAgaWYgKGF1dGhQb3J0ID09PSAnODA4MicpIHtcclxuICAgICAgICAgICAgcmV0dXJuIHBhdGgucmVwbGFjZSgnL2FwaS94YW1vcHMvdXNlci9wcm9maWxlJywgJy9hcGkvYmlsbG9wcy9wcm9maWxlJyk7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgICByZXR1cm4gcGF0aDsgLy8gS2VlcCBhcy1pcyBmb3IgWGFtT3BzXHJcbiAgICAgICAgfVxyXG4gICAgICB9LFxyXG5cclxuICAgICAgLy8gLS0tIDMuIFNIQVJFRCBNT0RVTEVTIC0tLVxyXG4gICAgICAvLyBSb3V0ZXMgQWNjb3VudCBNYW5hZ2VyIHJlcXVlc3RzIHRvIHRoZSBhY3RpdmUgYmFja2VuZFxyXG4gICAgICAnL2FwaS94YW1vcHMvYWNjb3VudC1tYW5hZ2VyJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldCxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZVxyXG4gICAgICB9LFxyXG4gICAgICAvLyBSb3V0ZXMgRGFzaGJvYXJkIERhdGEgcmVxdWVzdHMgdG8gdGhlIGFjdGl2ZSBiYWNrZW5kIChGaXhlcyBFQ09OTlJFRlVTRUQpXHJcbiAgICAgICcvYXBpL3hhbW9wcy9kYXNoYm9hcmQnOiB7XHJcbiAgICAgICAgdGFyZ2V0OiBhdXRoVGFyZ2V0LFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlXHJcbiAgICAgIH0sXHJcblxyXG4gICAgICAvLyAtLS0gNC4gQ0xPVUQgUFJPVklERVIgQVBJcyAtLS1cclxuICAgICAgLy8gVGhlc2UgZmFsbGJhY2tzIHBvaW50IHRvICdhdXRoVGFyZ2V0JyB0byBoYW5kbGUgY2FzZXMgd2hlcmUgY29kZSByZXNpZGVzIGluIGRpZmZlcmVudCBzZXJ2aWNlc1xyXG4gICAgICAnL2FwaS9henVyZSc6IHsgdGFyZ2V0OiBhdXRoVGFyZ2V0LCBjaGFuZ2VPcmlnaW46IHRydWUsIHNlY3VyZTogZmFsc2UgfSxcclxuICAgICAgJy9hcGkvYXdzJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LFxyXG4gICAgICAnL2FwaS9nY3AnOiB7IHRhcmdldDogYXV0aFRhcmdldCwgY2hhbmdlT3JpZ2luOiB0cnVlLCBzZWN1cmU6IGZhbHNlIH0sXHJcblxyXG4gICAgICAvLyAtLS0gNS4gU1BFQ0lGSUMgU0VSVklDRSBFTkRQT0lOVFMgLS0tXHJcbiAgICAgIC8vIEFkbWluICYgQmlsbG9wcyBBUEkgcmVxdWVzdHMgYWx3YXlzIGdvIHRvIDgwODIgKGJpbGxvcHMtc2VydmljZSlcclxuICAgICAgJy9hcGkvYWRtaW4nOiB7XHJcbiAgICAgICAgdGFyZ2V0OiAnaHR0cDovL2xvY2FsaG9zdDo4MDgyJyxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcclxuICAgICAgfSxcclxuICAgICAgJy9hcGkvYmlsbG9wcyc6IHtcclxuICAgICAgICB0YXJnZXQ6ICdodHRwOi8vbG9jYWxob3N0OjgwODInLFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlLFxyXG4gICAgICB9LFxyXG4gICAgICAvLyBHZW5lcmFsIFhhbU9wcyByZXF1ZXN0cyAoY2F0Y2gtYWxsIGZvciBvdGhlciB4YW1vcHMgZW5kcG9pbnRzKVxyXG4gICAgICAnL2FwaS94YW1vcHMnOiB7XHJcbiAgICAgICAgdGFyZ2V0OiAnaHR0cDovL2xvY2FsaG9zdDo4MDgwJyxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZVxyXG4gICAgICB9LFxyXG5cclxuICAgICAgLy8gRmVhdHVyZSBzcGVjaWZpYyBlbmRwb2ludHNcclxuICAgICAgJy9hcGkvYWktYWR2aXNvcic6IHsgdGFyZ2V0OiBhdXRoVGFyZ2V0LCBjaGFuZ2VPcmlnaW46IHRydWUsIHNlY3VyZTogZmFsc2UgfSxcclxuICAgICAgJy9hcGkvY2ljZCc6IHsgdGFyZ2V0OiBhdXRoVGFyZ2V0LCBjaGFuZ2VPcmlnaW46IHRydWUsIHNlY3VyZTogZmFsc2UgfSxcclxuICAgICAgJy9hcGkva2FycGVudGVyJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LFxyXG5cclxuICAgICAgLy8gLS0tIDYuIFdFQlNPQ0tFVFMgLS0tXHJcbiAgICAgIC8vIEV4cGxpY2l0IHByb3h5IGZvciB0aGUgcmVuYW1lZCAvdGVybWluYWwgZW5kcG9pbnRcclxuICAgICAgJy90ZXJtaW5hbCc6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQucmVwbGFjZSgnaHR0cCcsICd3cycpLCAvLyBQcm94aWVzIHRvIHdzOi8vbG9jYWxob3N0OjgwODAvdGVybWluYWxcclxuICAgICAgICB3czogdHJ1ZSxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZVxyXG4gICAgICB9LFxyXG4gICAgICAvLyBHZW5lcmFsIFdlYlNvY2tldCBwcm94eVxyXG4gICAgICAnL3dzJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldC5yZXBsYWNlKCdodHRwJywgJ3dzJyksXHJcbiAgICAgICAgd3M6IHRydWUsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIGZvbGxvd1JlZGlyZWN0czogZmFsc2UsXHJcbiAgICAgICAgcmV3cml0ZVdzT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHRpbWVvdXQ6IDEwMDAwLFxyXG4gICAgICB9LFxyXG4gICAgfVxyXG4gIH0sXHJcblxyXG4gIGJ1aWxkOiB7XHJcbiAgICByb2xsdXBPcHRpb25zOiB7XHJcbiAgICAgIGlucHV0OiB7XHJcbiAgICAgICAgaW5kZXg6IHJlc29sdmUoX19kaXJuYW1lLCAnaW5kZXguaHRtbCcpLFxyXG4gICAgICAgICdhY2NvdW50LW1hbmFnZXInOiByZXNvbHZlKF9fZGlybmFtZSwgJ2FjY291bnQtbWFuYWdlci5odG1sJyksXHJcbiAgICAgICAgJ3VzZXItbWFuYWdlcic6IHJlc29sdmUoX19kaXJuYW1lLCAndXNlci1tYW5hZ2VyLmh0bWwnKSxcclxuICAgICAgICAnWGFtb3BzX1VzZXJfbWFuYWdlbWVudC5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdYYW1vcHNfVXNlcl9tYW5hZ2VtZW50Lmh0bWwnKSxcclxuICAgICAgICAnYWRkLWFjY291bnQnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2FkZC1hY2NvdW50Lmh0bWwnKSxcclxuICAgICAgICAnYWRkLWdjcC1hY2NvdW50JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhZGQtZ2NwLWFjY291bnQuaHRtbCcpLFxyXG4gICAgICAgIGFsZXJ0czogcmVzb2x2ZShfX2Rpcm5hbWUsICdhbGVydHMuaHRtbCcpLFxyXG4gICAgICAgIGNsb3VkazhzOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Nsb3VkazhzLmh0bWwnKSxcclxuICAgICAgICBjbG91ZGxpc3Q6IHJlc29sdmUoX19kaXJuYW1lLCAnY2xvdWRsaXN0Lmh0bWwnKSxcclxuICAgICAgICBjbG91ZG1hcDogcmVzb2x2ZShfX2Rpcm5hbWUsICdjbG91ZG1hcC5odG1sJyksXHJcbiAgICAgICAgY29zdDogcmVzb2x2ZShfX2Rpcm5hbWUsICdjb3N0Lmh0bWwnKSxcclxuICAgICAgICBkYXNoYm9hcmQ6IHJlc29sdmUoX19kaXJuYW1lLCAnZGFzaGJvYXJkLmh0bWwnKSxcclxuICAgICAgICAnZWtzLWRldGFpbHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Vrcy1kZXRhaWxzLmh0bWwnKSxcclxuICAgICAgICBmaW5vcHM6IHJlc29sdmUoX19kaXJuYW1lLCAnZmlub3BzLmh0bWwnKSxcclxuICAgICAgICByaWdodHNpemluZzogcmVzb2x2ZShfX2Rpcm5hbWUsICdyaWdodHNpemluZy5odG1sJyksXHJcbiAgICAgICAgcmVzZXJ2YXRpb246IHJlc29sdmUoX19kaXJuYW1lLCAncmVzZXJ2YXRpb24uaHRtbCcpLFxyXG4gICAgICAgIHdhc3RlOiByZXNvbHZlKF9fZGlybmFtZSwgJ3dhc3RlLmh0bWwnKSxcclxuICAgICAgICBwZXJmb3JtYW5jZTogcmVzb2x2ZShfX2Rpcm5hbWUsICdwZXJmb3JtYW5jZS5odG1sJyksXHJcbiAgICAgICAgeGFtb3BzX3RpY2tldHM6IHJlc29sdmUoX19kaXJuYW1lLCAneGFtb3BzX3RpY2tldHMuaHRtbCcpLFxyXG4gICAgICAgIGdjcF94YW1vcHNfdGlja2V0czogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfeGFtb3BzX3RpY2tldHMuaHRtbCcpLFxyXG4gICAgICAgIHhhbW9wc190aWNrZXRfZGV0YWlsOiByZXNvbHZlKF9fZGlybmFtZSwgJ3hhbW9wc190aWNrZXRfZGV0YWlsLmh0bWwnKSxcclxuICAgICAgICBnY3BfeGFtb3BzX3RpY2tldF9kZXRhaWw6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX3hhbW9wc190aWNrZXRfZGV0YWlsLmh0bWwnKSxcclxuICAgICAgICAnZ3JhZmFuYS1kYXNoYm9hcmQuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ3JhZmFuYS1kYXNoYm9hcmQuaHRtbCcpLFxyXG4gICAgICAgICdkZXZvcHNfaW5fdGhlX2JveC5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdkZXZvcHNfaW5fdGhlX2JveC5odG1sJyksXHJcbiAgICAgICAgJ2djcF9kZXZvcHNfaW5fdGhlX2JveC5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfZGV2b3BzX2luX3RoZV9ib3guaHRtbCcpLFxyXG4gICAgICAgICdjaWNkX3BpcGVsaW5lcy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdjaWNkX3BpcGVsaW5lcy5odG1sJyksXHJcbiAgICAgICAgc2VjdXJpdHk6IHJlc29sdmUoX19kaXJuYW1lLCAnc2VjdXJpdHkuaHRtbCcpLFxyXG4gICAgICAgICdhZGQtZ2l0aHViLWNvbmZpZy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhZGQtZ2l0aHViLWNvbmZpZy5odG1sJyksXHJcbiAgICAgICAgJ3NvbmFycXViZS5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdzb25hcnF1YmUuaHRtbCcpLFxyXG4gICAgICAgICdhaW9wcy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhaW9wcy5odG1sJyksXHJcbiAgICAgICAgJ2NvbXBsaWFuY2VvcHMuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnY29tcGxpYW5jZW9wcy5odG1sJyksXHJcbiAgICAgICAgJ2RhdGFvcHMuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZGF0YW9wcy5odG1sJyksXHJcbiAgICAgICAgJ2Nsb3Vkc2hlbGwuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnY2xvdWRzaGVsbC5odG1sJyksXHJcbiAgICAgICAgJ3Nwb3QtYXV0b21hdGlvbi5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdzcG90LWF1dG9tYXRpb24uaHRtbCcpLFxyXG4gICAgICAgICdhY3Rpb25zLWhpc3RvcnkuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYWN0aW9ucy1oaXN0b3J5Lmh0bWwnKSxcclxuICAgICAgICAnbGF1bmNoLWFuYWx5dGljcy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdsYXVuY2gtYW5hbHl0aWNzLmh0bWwnKSxcclxuICAgICAgICAncmlzcC1jb3ZlcmFnZS5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdyaXNwLWNvdmVyYWdlLmh0bWwnKSxcclxuICAgICAgICAvLyBBZG1pbiBzdWJkaXIgZmlsZXNcclxuICAgICAgICAnYWRtaW5fY3JlZGl0cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl9jcmVkaXRzLmh0bWwnKSxcclxuICAgICAgICAnYWRtaW5faW52b2ljZV9kZXRhaWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYWRtaW5faW52b2ljZV9kZXRhaWwuaHRtbCcpLFxyXG4gICAgICAgICdhZG1pbl9pbnZvaWNlcyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl9pbnZvaWNlcy5odG1sJyksXHJcbiAgICAgICAgJ2FkbWluX3RpY2tldHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYWRtaW5fdGlja2V0cy5odG1sJyksXHJcbiAgICAgICAgJ2JpbGxpbmcnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYmlsbGluZy5odG1sJyksXHJcbiAgICAgICAgJ3RpY2tldHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvdGlja2V0cy5odG1sJyksXHJcbiAgICAgICAgJ2FkbWluX2FncmVlbWVudHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYWRtaW5fYWdyZWVtZW50cy5odG1sJyksXHJcbiAgICAgICAgJ2FncmVlbWVudHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYWdyZWVtZW50cy5odG1sJyksXHJcbiAgICAgICAgJ3RpY2tldF9kZXRhaWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvdGlja2V0X2RldGFpbC5odG1sJyksXHJcbiAgICAgICAgJ2NyZWRpdHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvY3JlZGl0cy5odG1sJyksXHJcbiAgICAgICAgJ2ludm9pY2VzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2ludm9pY2VzLmh0bWwnKSxcclxuICAgICAgICAnZGFzaGJvYXJkX2FkbWluJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2Rhc2hib2FyZF9hZG1pbi5odG1sJyksXHJcbiAgICAgICAgJ2Rhc2hib2FyZF91c2VyJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2Rhc2hib2FyZF91c2VyLmh0bWwnKSxcclxuICAgICAgICAnYWRtaW5fZGFzaGJvYXJkLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYWRtaW5fZGFzaGJvYXJkLmh0bWwnKSxcclxuICAgICAgICAnYWRtaW5fY2xvdWRmcm9udF9iaWxsaW5nJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2FkbWluX2Nsb3VkZnJvbnRfYmlsbGluZy5odG1sJyksXHJcbiAgICAgICAgJ21hcmtldHBsYWNlLXB1cmNoYXNlcyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9tYXJrZXRwbGFjZS1wdXJjaGFzZXMuaHRtbCcpLFxyXG4gICAgICAgICd0aGlyZHBhcnR5LXRvb2xzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL3RoaXJkcGFydHktdG9vbHMuaHRtbCcpLFxyXG4gICAgICAgICd3b3Jrc3BhY2UtbGljZW5zZXMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvd29ya3NwYWNlLWxpY2Vuc2VzLmh0bWwnKSxcclxuICAgICAgICAndW5pZmllZC1kYXNoYm9hcmQnOiByZXNvbHZlKF9fZGlybmFtZSwgJ3VuaWZpZWQtZGFzaGJvYXJkLmh0bWwnKSxcclxuICAgICAgICAnY2xvdWRzaXR0ZXIuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnY2xvdWRzaXR0ZXIuaHRtbCcpLFxyXG4gICAgICAgICdjYWxjdWxhdG9yLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2NhbGN1bGF0b3IuaHRtbCcpLFxyXG5cclxuICAgICAgICAvLyBHQ1Agc3ViZGlyIGZpbGVzXHJcbiAgICAgICAgJ2djcF9jbG91ZGxpc3QnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9jbG91ZGxpc3QuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfY2xvdWRtYXAnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9jbG91ZG1hcC5odG1sJyksXHJcbiAgICAgICAgJ2djcF9jb3N0JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfY29zdC5odG1sJyksXHJcbiAgICAgICAgJ2djcF9kYXNoYm9hcmQnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9kYXNoYm9hcmQuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfZmlub3BzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfZmlub3BzLmh0bWwnKSxcclxuICAgICAgICAnZ2NwLWJpbGxpbmcnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvZ2NwLWJpbGxpbmcuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfcmlnaHRzaXppbmcnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9yaWdodHNpemluZy5odG1sJyksXHJcbiAgICAgICAgJ2djcF9zZWN1cml0eSc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX3NlY3VyaXR5Lmh0bWwnKSxcclxuICAgICAgICAnZ2NwX3dhc3RlJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3Bfd2FzdGUuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfY2xvdWRrOHMuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX2Nsb3VkazhzLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX3BlcmZvcm1hbmNlLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9wZXJmb3JtYW5jZS5odG1sJyksXHJcbiAgICAgICAgJ2djcF9yZXNlcnZhdGlvbnMuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX3Jlc2VydmF0aW9ucy5odG1sJyksXHJcbiAgICAgICAgJ2djcF9hbGVydHMuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX2FsZXJ0cy5odG1sJyksXHJcblxyXG4gICAgICAgIC8vIEF6dXJlIGZpbGVzXHJcbiAgICAgICAgJ2F6dXJlX2Nsb3VkbGlzdCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYXp1cmVfY2xvdWRsaXN0Lmh0bWwnKSxcclxuICAgICAgICAnYXp1cmVfZGFzaGJvYXJkJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhenVyZV9kYXNoYm9hcmQuaHRtbCcpLFxyXG4gICAgICAgICdhenVyZS1maW5vcHMtcmVwb3J0JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhenVyZS1maW5vcHMtcmVwb3J0Lmh0bWwnKSxcclxuICAgICAgICAnc3VwZXJhZG1pbl9jdXN0b21lci5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdzdXBlcmFkbWluX2N1c3RvbWVyLmh0bWwnKVxyXG4gICAgICB9LFxyXG4gICAgfSxcclxuICB9LFxyXG59KTsiXSwKICAibWFwcGluZ3MiOiAiO0FBQWtULFNBQVMsb0JBQW9CO0FBQy9VLFNBQVMsZUFBZTtBQUN4QixPQUFPLGlCQUFpQjtBQUZ4QixJQUFNLG1DQUFtQztBQU16QyxJQUFNLFdBQVcsUUFBUSxJQUFJLGFBQWE7QUFDMUMsSUFBTSxhQUFhLG9C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USxrQ0FBVyxzQkFBc0I7QUFBQSxRQUNwRCxZQUFZLFFBQVEsa0NBQVcsdUJBQXVCO0FBQUEsUUFDdEQsbUJBQW1CLFFBQVEsa0NBQVcsOEJBQThCO0FBQUEsUUFDcEUsa0JBQWtCLFFBQVEsa0NBQVcsNkJBQTZCO0FBQUEsUUFDbEUsd0JBQXdCLFFBQVEsa0NBQVcsOEJBQThCO0FBQUEsUUFDekUsNEJBQTRCLFFBQVEsa0NBQVcsdUNBQXVDO0FBQUEsUUFDdEYseUJBQXlCLFFBQVEsa0NBQVcsb0NBQW9DO0FBQUEsUUFDaEYsb0JBQW9CLFFBQVEsa0NBQVcsK0JBQStCO0FBQUEsUUFDdEUsc0JBQXNCLFFBQVEsa0NBQVcsaUNBQWlDO0FBQUEsUUFDMUUscUJBQXFCLFFBQVEsa0NBQVcsd0JBQXdCO0FBQUEsUUFDaEUsb0JBQW9CLFFBQVEsa0NBQVcsa0JBQWtCO0FBQUEsUUFDekQsbUJBQW1CLFFBQVEsa0NBQVcsaUJBQWlCO0FBQUE7QUFBQSxRQUd2RCxpQkFBaUIsUUFBUSxrQ0FBVyxvQkFBb0I7QUFBQSxRQUN4RCxnQkFBZ0IsUUFBUSxrQ0FBVyxtQkFBbUI7QUFBQSxRQUN0RCxZQUFZLFFBQVEsa0NBQVcsZUFBZTtBQUFBLFFBQzlDLGlCQUFpQixRQUFRLGtDQUFXLG9CQUFvQjtBQUFBLFFBQ3hELGNBQWMsUUFBUSxrQ0FBVyxpQkFBaUI7QUFBQSxRQUNsRCxlQUFlLFFBQVEsa0NBQVcsMEJBQTBCO0FBQUEsUUFDNUQsbUJBQW1CLFFBQVEsa0NBQVcsc0JBQXNCO0FBQUEsUUFDNUQsZ0JBQWdCLFFBQVEsa0NBQVcsbUJBQW1CO0FBQUEsUUFDdEQsYUFBYSxRQUFRLGtDQUFXLGdCQUFnQjtBQUFBLFFBQ2hELHFCQUFxQixRQUFRLGtDQUFXLG1CQUFtQjtBQUFBLFFBQzNELHdCQUF3QixRQUFRLGtDQUFXLHNCQUFzQjtBQUFBLFFBQ2pFLHlCQUF5QixRQUFRLGtDQUFXLHVCQUF1QjtBQUFBLFFBQ25FLG1CQUFtQixRQUFRLGtDQUFXLGlCQUFpQjtBQUFBO0FBQUEsUUFHdkQsbUJBQW1CLFFBQVEsa0NBQVcsc0JBQXNCO0FBQUEsUUFDNUQsbUJBQW1CLFFBQVEsa0NBQVcsc0JBQXNCO0FBQUEsUUFDNUQsdUJBQXVCLFFBQVEsa0NBQVcsMEJBQTBCO0FBQUEsUUFDcEUsNEJBQTRCLFFBQVEsa0NBQVcsMEJBQTBCO0FBQUEsTUFDM0U7QUFBQSxJQUNGO0FBQUEsRUFDRjtBQUNGLENBQUM7IiwKICAibmFtZXMiOiBbXQp9Cg==
