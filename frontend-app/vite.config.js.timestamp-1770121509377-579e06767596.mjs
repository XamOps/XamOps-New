// vite.config.js
import { defineConfig } from "file:///C:/Users/dell/Projects/XamOps-New/frontend-app/node_modules/vite/dist/node/index.js";
import { resolve } from "path";
import htmlInclude from "file:///C:/Users/dell/Projects/XamOps-New/frontend-app/node_modules/vite-plugin-html-include/dist/index.js";
var __vite_injected_original_dirname = "C:\\Users\\dell\\Projects\\XamOps-New\\frontend-app";
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
      "/api/ai": { target: authTarget, changeOrigin: true, secure: false },
      // ✅ Added missing proxy rule
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
        "support-dashboard": resolve(__vite_injected_original_dirname, "billops/support-dashboard.html"),
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
        "azure-billing": resolve(__vite_injected_original_dirname, "billops/azure-billing.html"),
        "billops-login": resolve(__vite_injected_original_dirname, "billops/login.html"),
        "gcp_rightsizing": resolve(__vite_injected_original_dirname, "gcp_rightsizing.html"),
        "gcp_security": resolve(__vite_injected_original_dirname, "gcp_security.html"),
        "gcp_waste": resolve(__vite_injected_original_dirname, "gcp_waste.html"),
        "gcp_cloudk8s.html": resolve(__vite_injected_original_dirname, "gcp_cloudk8s.html"),
        "gcp_performance.html": resolve(__vite_injected_original_dirname, "gcp_performance.html"),
        "gcp_reservations.html": resolve(__vite_injected_original_dirname, "gcp_reservations.html"),
        "gcp_alerts.html": resolve(__vite_injected_original_dirname, "gcp_alerts.html"),
        "user-manager.html": resolve(__vite_injected_original_dirname, "user-manager.html"),
        "my-profile.html": resolve(__vite_injected_original_dirname, "my-profile.html"),
        // Azure files
        "azure_cloudlist": resolve(__vite_injected_original_dirname, "azure_cloudlist.html"),
        "azure_dashboard": resolve(__vite_injected_original_dirname, "azure_dashboard.html"),
        "azure-finops-report": resolve(__vite_injected_original_dirname, "azure-finops-report.html"),
        "superadmin_customers": resolve(__vite_injected_original_dirname, "superadmin_customers.html")
        // Integration guide
      }
    }
  }
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcuanMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCJDOlxcXFxVc2Vyc1xcXFxkZWxsXFxcXFByb2plY3RzXFxcXFhhbU9wcy1OZXdcXFxcZnJvbnRlbmQtYXBwXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ZpbGVuYW1lID0gXCJDOlxcXFxVc2Vyc1xcXFxkZWxsXFxcXFByb2plY3RzXFxcXFhhbU9wcy1OZXdcXFxcZnJvbnRlbmQtYXBwXFxcXHZpdGUuY29uZmlnLmpzXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ltcG9ydF9tZXRhX3VybCA9IFwiZmlsZTovLy9DOi9Vc2Vycy9kZWxsL1Byb2plY3RzL1hhbU9wcy1OZXcvZnJvbnRlbmQtYXBwL3ZpdGUuY29uZmlnLmpzXCI7aW1wb3J0IHsgZGVmaW5lQ29uZmlnIH0gZnJvbSAndml0ZSc7XHJcbmltcG9ydCB7IHJlc29sdmUgfSBmcm9tICdwYXRoJztcclxuaW1wb3J0IGh0bWxJbmNsdWRlIGZyb20gJ3ZpdGUtcGx1Z2luLWh0bWwtaW5jbHVkZSc7XHJcblxyXG4vLyAtLS0gQ09ORklHVVJBVElPTiAtLS1cclxuLy8gRGVmaW5lIHRoZSBkZWZhdWx0IGF1dGggcG9ydCBhbmQgdGFyZ2V0XHJcbmNvbnN0IGF1dGhQb3J0ID0gcHJvY2Vzcy5lbnYuQVVUSF9QT1JUIHx8ICc4MDgwJztcclxuY29uc3QgYXV0aFRhcmdldCA9IGBodHRwOi8vbG9jYWxob3N0OiR7YXV0aFBvcnR9YDtcclxuXHJcbi8vIC0tLSBIRUxQRVIgRlVOQ1RJT04gLS0tXHJcbi8vIFRoaXMgZnVuY3Rpb24gcmV3cml0ZXMgdGhlIGJhY2tlbmQncyBjb29raWUgdG8gd29yayB3aXRoIHRoZSBmcm9udGVuZCBwcm94eVxyXG5jb25zdCBjb29raWVSZXdyaXRlID0gKHByb3h5UmVzLCByZXEpID0+IHtcclxuICBjb25zdCBjb29raWVzID0gcHJveHlSZXMuaGVhZGVyc1snc2V0LWNvb2tpZSddO1xyXG4gIGlmIChjb29raWVzKSB7XHJcbiAgICBjb25zdCBuZXdDb29raWVzID0gY29va2llcy5tYXAoY29va2llID0+XHJcbiAgICAgIGNvb2tpZVxyXG4gICAgICAgIC5yZXBsYWNlKC87IHBhdGg9XFwvLio/KDt8JCkvLCAnOyBwYXRoPS87JykgLy8gU2V0IFBhdGggdG8gcm9vdFxyXG4gICAgICAgIC5yZXBsYWNlKC87IGRvbWFpbj0uKj8oO3wkKS8sIGA7IGRvbWFpbj0ke3JlcS5oZWFkZXJzLmhvc3Quc3BsaXQoJzonKVswXX07YCkgLy8gU2V0IERvbWFpbiB0byBmcm9udGVuZFxyXG4gICAgKTtcclxuICAgIHByb3h5UmVzLmhlYWRlcnNbJ3NldC1jb29raWUnXSA9IG5ld0Nvb2tpZXM7XHJcbiAgfVxyXG59O1xyXG5cclxuZXhwb3J0IGRlZmF1bHQgZGVmaW5lQ29uZmlnKHtcclxuICBwbHVnaW5zOiBbXHJcbiAgICBodG1sSW5jbHVkZSgpLFxyXG4gIF0sXHJcblxyXG4gIHNlcnZlcjoge1xyXG4gICAgcG9ydDogNTE3MyxcclxuICAgIHByb3h5OiB7XHJcbiAgICAgIC8vIC0tLSAxLiBDT1JFIEFVVEhFTlRJQ0FUSU9OIC0tLVxyXG4gICAgICAnL2xvZ2luJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldCxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcclxuICAgICAgICBjb25maWd1cmU6IChwcm94eSwgb3B0aW9ucykgPT4ge1xyXG4gICAgICAgICAgcHJveHkub24oJ3Byb3h5UmVzJywgY29va2llUmV3cml0ZSk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9LFxyXG4gICAgICAnL2xvZ291dCc6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXHJcbiAgICAgICAgY29uZmlndXJlOiAocHJveHksIG9wdGlvbnMpID0+IHtcclxuICAgICAgICAgIHByb3h5Lm9uKCdwcm94eVJlcycsIGNvb2tpZVJld3JpdGUpO1xyXG4gICAgICAgIH1cclxuICAgICAgfSxcclxuICAgICAgLy8gXHUyNzA1IEFEREVEOiBCcmlkZ2UgZm9yIFBhc3N3b3JkIFJlc2V0IEZlYXR1cmVcclxuICAgICAgJy9hcGkvYXV0aCc6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXHJcbiAgICAgIH0sXHJcblxyXG4gICAgICAvLyAtLS0gMi4gVVNFUiBQUk9GSUxFIChGaXhlcyBTaWRlYmFyIFZpc2liaWxpdHkpIC0tLVxyXG4gICAgICAvLyBJbnRlcmNlcHRzIFhhbU9wcyBwcm9maWxlIGNhbGwgYW5kIHJld3JpdGVzIGl0IHRvIEJpbGxPcHMgZm9ybWF0IGlmIG5lZWRlZFxyXG4gICAgICAnL2FwaS94YW1vcHMvdXNlci9wcm9maWxlJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldCxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcclxuICAgICAgICByZXdyaXRlOiAocGF0aCkgPT4ge1xyXG4gICAgICAgICAgaWYgKGF1dGhQb3J0ID09PSAnODA4MicpIHtcclxuICAgICAgICAgICAgcmV0dXJuIHBhdGgucmVwbGFjZSgnL2FwaS94YW1vcHMvdXNlci9wcm9maWxlJywgJy9hcGkvYmlsbG9wcy9wcm9maWxlJyk7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgICByZXR1cm4gcGF0aDsgLy8gS2VlcCBhcy1pcyBmb3IgWGFtT3BzXHJcbiAgICAgICAgfVxyXG4gICAgICB9LFxyXG5cclxuICAgICAgLy8gLS0tIDMuIFNIQVJFRCBNT0RVTEVTIC0tLVxyXG4gICAgICAvLyBSb3V0ZXMgQWNjb3VudCBNYW5hZ2VyIHJlcXVlc3RzIHRvIHRoZSBhY3RpdmUgYmFja2VuZFxyXG4gICAgICAnL2FwaS94YW1vcHMvYWNjb3VudC1tYW5hZ2VyJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldCxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZVxyXG4gICAgICB9LFxyXG4gICAgICAvLyBSb3V0ZXMgRGFzaGJvYXJkIERhdGEgcmVxdWVzdHMgdG8gdGhlIGFjdGl2ZSBiYWNrZW5kIChGaXhlcyBFQ09OTlJFRlVTRUQpXHJcbiAgICAgICcvYXBpL3hhbW9wcy9kYXNoYm9hcmQnOiB7XHJcbiAgICAgICAgdGFyZ2V0OiBhdXRoVGFyZ2V0LFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlXHJcbiAgICAgIH0sXHJcblxyXG4gICAgICAvLyAtLS0gNC4gQ0xPVUQgUFJPVklERVIgQVBJcyAtLS1cclxuICAgICAgLy8gVGhlc2UgZmFsbGJhY2tzIHBvaW50IHRvICdhdXRoVGFyZ2V0JyB0byBoYW5kbGUgY2FzZXMgd2hlcmUgY29kZSByZXNpZGVzIGluIGRpZmZlcmVudCBzZXJ2aWNlc1xyXG4gICAgICAnL2FwaS9henVyZSc6IHsgdGFyZ2V0OiBhdXRoVGFyZ2V0LCBjaGFuZ2VPcmlnaW46IHRydWUsIHNlY3VyZTogZmFsc2UgfSxcclxuICAgICAgJy9hcGkvYXdzJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LFxyXG4gICAgICAnL2FwaS9nY3AnOiB7IHRhcmdldDogYXV0aFRhcmdldCwgY2hhbmdlT3JpZ2luOiB0cnVlLCBzZWN1cmU6IGZhbHNlIH0sXHJcblxyXG4gICAgICAvLyAtLS0gNS4gU1BFQ0lGSUMgU0VSVklDRSBFTkRQT0lOVFMgLS0tXHJcbiAgICAgIC8vIEFkbWluICYgQmlsbG9wcyBBUEkgcmVxdWVzdHMgYWx3YXlzIGdvIHRvIDgwODIgKGJpbGxvcHMtc2VydmljZSlcclxuICAgICAgJy9hcGkvYWRtaW4nOiB7XHJcbiAgICAgICAgdGFyZ2V0OiAnaHR0cDovL2xvY2FsaG9zdDo4MDgyJyxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcclxuICAgICAgfSxcclxuICAgICAgJy9hcGkvYmlsbG9wcyc6IHtcclxuICAgICAgICB0YXJnZXQ6ICdodHRwOi8vbG9jYWxob3N0OjgwODInLFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlLFxyXG4gICAgICB9LFxyXG4gICAgICAvLyBHZW5lcmFsIFhhbU9wcyByZXF1ZXN0cyAoY2F0Y2gtYWxsIGZvciBvdGhlciB4YW1vcHMgZW5kcG9pbnRzKVxyXG4gICAgICAnL2FwaS94YW1vcHMnOiB7XHJcbiAgICAgICAgdGFyZ2V0OiAnaHR0cDovL2xvY2FsaG9zdDo4MDgwJyxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZVxyXG4gICAgICB9LFxyXG5cclxuICAgICAgLy8gRmVhdHVyZSBzcGVjaWZpYyBlbmRwb2ludHNcclxuICAgICAgJy9hcGkvYWktYWR2aXNvcic6IHsgdGFyZ2V0OiBhdXRoVGFyZ2V0LCBjaGFuZ2VPcmlnaW46IHRydWUsIHNlY3VyZTogZmFsc2UgfSxcclxuICAgICAgJy9hcGkvY2ljZCc6IHsgdGFyZ2V0OiBhdXRoVGFyZ2V0LCBjaGFuZ2VPcmlnaW46IHRydWUsIHNlY3VyZTogZmFsc2UgfSxcclxuICAgICAgJy9hcGkva2FycGVudGVyJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LFxyXG4gICAgICAnL2FwaS9haSc6IHsgdGFyZ2V0OiBhdXRoVGFyZ2V0LCBjaGFuZ2VPcmlnaW46IHRydWUsIHNlY3VyZTogZmFsc2UgfSwgLy8gXHUyNzA1IEFkZGVkIG1pc3NpbmcgcHJveHkgcnVsZVxyXG5cclxuXHJcbiAgICAgIC8vIC0tLSA2LiBXRUJTT0NLRVRTIC0tLVxyXG4gICAgICAvLyBFeHBsaWNpdCBwcm94eSBmb3IgdGhlIHJlbmFtZWQgL3Rlcm1pbmFsIGVuZHBvaW50XHJcbiAgICAgICcvdGVybWluYWwnOiB7XHJcbiAgICAgICAgdGFyZ2V0OiBhdXRoVGFyZ2V0LnJlcGxhY2UoJ2h0dHAnLCAnd3MnKSwgLy8gUHJveGllcyB0byB3czovL2xvY2FsaG9zdDo4MDgwL3Rlcm1pbmFsXHJcbiAgICAgICAgd3M6IHRydWUsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2VcclxuICAgICAgfSxcclxuICAgICAgLy8gR2VuZXJhbCBXZWJTb2NrZXQgcHJveHlcclxuICAgICAgJy93cyc6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQucmVwbGFjZSgnaHR0cCcsICd3cycpLFxyXG4gICAgICAgIHdzOiB0cnVlLFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBmb2xsb3dSZWRpcmVjdHM6IGZhbHNlLFxyXG4gICAgICAgIHJld3JpdGVXc09yaWdpbjogdHJ1ZSxcclxuICAgICAgICB0aW1lb3V0OiAxMDAwMCxcclxuICAgICAgfSxcclxuICAgIH1cclxuICB9LFxyXG5cclxuICBidWlsZDoge1xyXG4gICAgcm9sbHVwT3B0aW9uczoge1xyXG4gICAgICBpbnB1dDoge1xyXG4gICAgICAgIGluZGV4OiByZXNvbHZlKF9fZGlybmFtZSwgJ2luZGV4Lmh0bWwnKSxcclxuICAgICAgICAnYWNjb3VudC1tYW5hZ2VyJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhY2NvdW50LW1hbmFnZXIuaHRtbCcpLFxyXG4gICAgICAgICdYYW1vcHNfVXNlcl9tYW5hZ2VtZW50Lmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ1hhbW9wc19Vc2VyX21hbmFnZW1lbnQuaHRtbCcpLFxyXG4gICAgICAgICdhZGQtYWNjb3VudCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYWRkLWFjY291bnQuaHRtbCcpLFxyXG4gICAgICAgICdhZGQtZ2NwLWFjY291bnQnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2FkZC1nY3AtYWNjb3VudC5odG1sJyksXHJcbiAgICAgICAgYWxlcnRzOiByZXNvbHZlKF9fZGlybmFtZSwgJ2FsZXJ0cy5odG1sJyksXHJcbiAgICAgICAgY2xvdWRrOHM6IHJlc29sdmUoX19kaXJuYW1lLCAnY2xvdWRrOHMuaHRtbCcpLFxyXG4gICAgICAgIGNsb3VkbGlzdDogcmVzb2x2ZShfX2Rpcm5hbWUsICdjbG91ZGxpc3QuaHRtbCcpLFxyXG4gICAgICAgIGNsb3VkbWFwOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Nsb3VkbWFwLmh0bWwnKSxcclxuICAgICAgICBjb3N0OiByZXNvbHZlKF9fZGlybmFtZSwgJ2Nvc3QuaHRtbCcpLFxyXG4gICAgICAgIGRhc2hib2FyZDogcmVzb2x2ZShfX2Rpcm5hbWUsICdkYXNoYm9hcmQuaHRtbCcpLFxyXG4gICAgICAgICdla3MtZGV0YWlscyc6IHJlc29sdmUoX19kaXJuYW1lLCAnZWtzLWRldGFpbHMuaHRtbCcpLFxyXG4gICAgICAgIGZpbm9wczogcmVzb2x2ZShfX2Rpcm5hbWUsICdmaW5vcHMuaHRtbCcpLFxyXG4gICAgICAgIHJpZ2h0c2l6aW5nOiByZXNvbHZlKF9fZGlybmFtZSwgJ3JpZ2h0c2l6aW5nLmh0bWwnKSxcclxuICAgICAgICByZXNlcnZhdGlvbjogcmVzb2x2ZShfX2Rpcm5hbWUsICdyZXNlcnZhdGlvbi5odG1sJyksXHJcbiAgICAgICAgd2FzdGU6IHJlc29sdmUoX19kaXJuYW1lLCAnd2FzdGUuaHRtbCcpLFxyXG4gICAgICAgIHBlcmZvcm1hbmNlOiByZXNvbHZlKF9fZGlybmFtZSwgJ3BlcmZvcm1hbmNlLmh0bWwnKSxcclxuICAgICAgICB4YW1vcHNfdGlja2V0czogcmVzb2x2ZShfX2Rpcm5hbWUsICd4YW1vcHNfdGlja2V0cy5odG1sJyksXHJcbiAgICAgICAgZ2NwX3hhbW9wc190aWNrZXRzOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF94YW1vcHNfdGlja2V0cy5odG1sJyksXHJcbiAgICAgICAgeGFtb3BzX3RpY2tldF9kZXRhaWw6IHJlc29sdmUoX19kaXJuYW1lLCAneGFtb3BzX3RpY2tldF9kZXRhaWwuaHRtbCcpLFxyXG4gICAgICAgIGdjcF94YW1vcHNfdGlja2V0X2RldGFpbDogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfeGFtb3BzX3RpY2tldF9kZXRhaWwuaHRtbCcpLFxyXG4gICAgICAgICdncmFmYW5hLWRhc2hib2FyZC5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdncmFmYW5hLWRhc2hib2FyZC5odG1sJyksXHJcbiAgICAgICAgJ2Rldm9wc19pbl90aGVfYm94Lmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Rldm9wc19pbl90aGVfYm94Lmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2Rldm9wc19pbl90aGVfYm94Lmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9kZXZvcHNfaW5fdGhlX2JveC5odG1sJyksXHJcbiAgICAgICAgJ2NpY2RfcGlwZWxpbmVzLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2NpY2RfcGlwZWxpbmVzLmh0bWwnKSxcclxuICAgICAgICBzZWN1cml0eTogcmVzb2x2ZShfX2Rpcm5hbWUsICdzZWN1cml0eS5odG1sJyksXHJcbiAgICAgICAgJ2FkZC1naXRodWItY29uZmlnLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2FkZC1naXRodWItY29uZmlnLmh0bWwnKSxcclxuICAgICAgICAnc29uYXJxdWJlLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ3NvbmFycXViZS5odG1sJyksXHJcbiAgICAgICAgJ2Fpb3BzLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Fpb3BzLmh0bWwnKSxcclxuICAgICAgICAnY29tcGxpYW5jZW9wcy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdjb21wbGlhbmNlb3BzLmh0bWwnKSxcclxuICAgICAgICAnZGF0YW9wcy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdkYXRhb3BzLmh0bWwnKSxcclxuICAgICAgICAnY2xvdWRzaGVsbC5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdjbG91ZHNoZWxsLmh0bWwnKSxcclxuICAgICAgICAnc3BvdC1hdXRvbWF0aW9uLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ3Nwb3QtYXV0b21hdGlvbi5odG1sJyksXHJcbiAgICAgICAgJ2FjdGlvbnMtaGlzdG9yeS5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhY3Rpb25zLWhpc3RvcnkuaHRtbCcpLFxyXG4gICAgICAgICdsYXVuY2gtYW5hbHl0aWNzLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2xhdW5jaC1hbmFseXRpY3MuaHRtbCcpLFxyXG4gICAgICAgICdyaXNwLWNvdmVyYWdlLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ3Jpc3AtY292ZXJhZ2UuaHRtbCcpLFxyXG4gICAgICAgIC8vIEFkbWluIHN1YmRpciBmaWxlc1xyXG4gICAgICAgICdhZG1pbl9jcmVkaXRzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2FkbWluX2NyZWRpdHMuaHRtbCcpLFxyXG4gICAgICAgICdhZG1pbl9pbnZvaWNlX2RldGFpbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl9pbnZvaWNlX2RldGFpbC5odG1sJyksXHJcbiAgICAgICAgJ2FkbWluX2ludm9pY2VzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2FkbWluX2ludm9pY2VzLmh0bWwnKSxcclxuICAgICAgICAnYWRtaW5fdGlja2V0cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl90aWNrZXRzLmh0bWwnKSxcclxuICAgICAgICAnYmlsbGluZyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9iaWxsaW5nLmh0bWwnKSxcclxuICAgICAgICAndGlja2V0cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy90aWNrZXRzLmh0bWwnKSxcclxuICAgICAgICAnYWRtaW5fYWdyZWVtZW50cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl9hZ3JlZW1lbnRzLmh0bWwnKSxcclxuICAgICAgICAnYWdyZWVtZW50cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZ3JlZW1lbnRzLmh0bWwnKSxcclxuICAgICAgICAndGlja2V0X2RldGFpbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy90aWNrZXRfZGV0YWlsLmh0bWwnKSxcclxuICAgICAgICAnY3JlZGl0cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9jcmVkaXRzLmh0bWwnKSxcclxuICAgICAgICAnaW52b2ljZXMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvaW52b2ljZXMuaHRtbCcpLFxyXG4gICAgICAgICdkYXNoYm9hcmRfYWRtaW4nOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvZGFzaGJvYXJkX2FkbWluLmh0bWwnKSxcclxuICAgICAgICAnZGFzaGJvYXJkX3VzZXInOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvZGFzaGJvYXJkX3VzZXIuaHRtbCcpLFxyXG4gICAgICAgICdhZG1pbl9kYXNoYm9hcmQuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl9kYXNoYm9hcmQuaHRtbCcpLFxyXG4gICAgICAgICdhZG1pbl9jbG91ZGZyb250X2JpbGxpbmcnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYWRtaW5fY2xvdWRmcm9udF9iaWxsaW5nLmh0bWwnKSxcclxuICAgICAgICAnbWFya2V0cGxhY2UtcHVyY2hhc2VzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL21hcmtldHBsYWNlLXB1cmNoYXNlcy5odG1sJyksXHJcbiAgICAgICAgJ3RoaXJkcGFydHktdG9vbHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvdGhpcmRwYXJ0eS10b29scy5odG1sJyksXHJcbiAgICAgICAgJ3dvcmtzcGFjZS1saWNlbnNlcyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy93b3Jrc3BhY2UtbGljZW5zZXMuaHRtbCcpLFxyXG4gICAgICAgICdzdXBwb3J0LWRhc2hib2FyZCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9zdXBwb3J0LWRhc2hib2FyZC5odG1sJyksXHJcbiAgICAgICAgJ3VuaWZpZWQtZGFzaGJvYXJkJzogcmVzb2x2ZShfX2Rpcm5hbWUsICd1bmlmaWVkLWRhc2hib2FyZC5odG1sJyksXHJcbiAgICAgICAgJ2Nsb3Vkc2l0dGVyLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Nsb3Vkc2l0dGVyLmh0bWwnKSxcclxuICAgICAgICAnY2FsY3VsYXRvci5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdjYWxjdWxhdG9yLmh0bWwnKSxcclxuXHJcbiAgICAgICAgLy8gR0NQIHN1YmRpciBmaWxlc1xyXG4gICAgICAgICdnY3BfY2xvdWRsaXN0JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfY2xvdWRsaXN0Lmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2Nsb3VkbWFwJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfY2xvdWRtYXAuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfY29zdCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX2Nvc3QuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfZGFzaGJvYXJkJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfZGFzaGJvYXJkLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2Zpbm9wcyc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX2Zpbm9wcy5odG1sJyksXHJcbiAgICAgICAgJ2djcC1iaWxsaW5nJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2djcC1iaWxsaW5nLmh0bWwnKSxcclxuICAgICAgICAnYXp1cmUtYmlsbGluZyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9henVyZS1iaWxsaW5nLmh0bWwnKSxcclxuICAgICAgICAnYmlsbG9wcy1sb2dpbic6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9sb2dpbi5odG1sJyksXHJcbiAgICAgICAgJ2djcF9yaWdodHNpemluZyc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX3JpZ2h0c2l6aW5nLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX3NlY3VyaXR5JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3Bfc2VjdXJpdHkuaHRtbCcpLFxyXG4gICAgICAgICdnY3Bfd2FzdGUnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF93YXN0ZS5odG1sJyksXHJcbiAgICAgICAgJ2djcF9jbG91ZGs4cy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfY2xvdWRrOHMuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfcGVyZm9ybWFuY2UuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX3BlcmZvcm1hbmNlLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX3Jlc2VydmF0aW9ucy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfcmVzZXJ2YXRpb25zLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2FsZXJ0cy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfYWxlcnRzLmh0bWwnKSxcclxuICAgICAgICAndXNlci1tYW5hZ2VyLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ3VzZXItbWFuYWdlci5odG1sJyksXHJcbiAgICAgICAgJ215LXByb2ZpbGUuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnbXktcHJvZmlsZS5odG1sJyksXHJcblxyXG4gICAgICAgIC8vIEF6dXJlIGZpbGVzXHJcbiAgICAgICAgJ2F6dXJlX2Nsb3VkbGlzdCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYXp1cmVfY2xvdWRsaXN0Lmh0bWwnKSxcclxuICAgICAgICAnYXp1cmVfZGFzaGJvYXJkJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhenVyZV9kYXNoYm9hcmQuaHRtbCcpLFxyXG4gICAgICAgICdhenVyZS1maW5vcHMtcmVwb3J0JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhenVyZS1maW5vcHMtcmVwb3J0Lmh0bWwnKSxcclxuICAgICAgICAnc3VwZXJhZG1pbl9jdXN0b21lcnMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ3N1cGVyYWRtaW5fY3VzdG9tZXJzLmh0bWwnKSxcclxuXHJcbiAgICAgICAgLy8gSW50ZWdyYXRpb24gZ3VpZGVcclxuICAgICAgfSxcclxuICAgIH0sXHJcbiAgfSxcclxufSk7Il0sCiAgIm1hcHBpbmdzIjogIjtBQUF3VSxTQUFTLG9CQUFvQjtBQUNyVyxTQUFTLGVBQWU7QUFDeEIsT0FBTyxpQkFBaUI7QUFGeEIsSUFBTSxtQ0FBbUM7QUFNekMsSUFBTSxXQUFXLFFBQVEsSUFBSSxhQUFhO0FBQzFDLElBQU0sYUFBYSxvQkFBb0IsUUFBUTtBQUkvQyxJQUFNLGdCQUFnQixDQUFDLFVBQVUsUUFBUTtBQUN2QyxRQUFNLFVBQVUsU0FBUyxRQUFRLFlBQVk7QUFDN0MsTUFBSSxTQUFTO0FBQ1gsVUFBTSxhQUFhLFFBQVE7QUFBQSxNQUFJLFlBQzdCLE9BQ0csUUFBUSxxQkFBcUIsV0FBVyxFQUN4QyxRQUFRLHFCQUFxQixZQUFZLElBQUksUUFBUSxLQUFLLE1BQU0sR0FBRyxFQUFFLENBQUMsQ0FBQyxHQUFHO0FBQUE7QUFBQSxJQUMvRTtBQUNBLGFBQVMsUUFBUSxZQUFZLElBQUk7QUFBQSxFQUNuQztBQUNGO0FBRUEsSUFBTyxzQkFBUSxhQUFhO0FBQUEsRUFDMUIsU0FBUztBQUFBLElBQ1AsWUFBWTtBQUFBLEVBQ2Q7QUFBQSxFQUVBLFFBQVE7QUFBQSxJQUNOLE1BQU07QUFBQSxJQUNOLE9BQU87QUFBQTtBQUFBLE1BRUwsVUFBVTtBQUFBLFFBQ1Is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
