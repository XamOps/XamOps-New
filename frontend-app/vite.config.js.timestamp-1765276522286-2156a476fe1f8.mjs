// vite.config.js
import { defineConfig } from "file:///C:/xamops-uat/XamOps-New/frontend-app/node_modules/vite/dist/node/index.js";
import { resolve } from "path";
import htmlInclude from "file:///C:/xamops-uat/XamOps-New/frontend-app/node_modules/vite-plugin-html-include/dist/index.js";
var __vite_injected_original_dirname = "C:\\xamops-uat\\XamOps-New\\frontend-app";
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
        // Admin subdir files
        "admin_credits": resolve(__vite_injected_original_dirname, "billops/admin_credits.html"),
        "admin_invoice_detail": resolve(__vite_injected_original_dirname, "billops/admin_invoice_detail.html"),
        "admin_invoices": resolve(__vite_injected_original_dirname, "billops/admin_invoices.html"),
        "admin_tickets": resolve(__vite_injected_original_dirname, "billops/admin_tickets.html"),
        "billing": resolve(__vite_injected_original_dirname, "billops/billing.html"),
        "tickets": resolve(__vite_injected_original_dirname, "billops/tickets.html"),
        "ticket_detail": resolve(__vite_injected_original_dirname, "billops/ticket_detail.html"),
        "credits": resolve(__vite_injected_original_dirname, "billops/credits.html"),
        "invoices": resolve(__vite_injected_original_dirname, "billops/invoices.html"),
        "admin_cloudfront_billing": resolve(__vite_injected_original_dirname, "billops/admin_cloudfront_billing.html"),
        "marketplace-purchases": resolve(__vite_injected_original_dirname, "billops/marketplace-purchases.html"),
        "thirdparty-tools": resolve(__vite_injected_original_dirname, "billops/thirdparty-tools.html"),
        "workspace-licenses": resolve(__vite_injected_original_dirname, "billops/workspace-licenses.html"),
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
        "azure-finops-report": resolve(__vite_injected_original_dirname, "azure-finops-report.html")
      }
    }
  }
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcuanMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCJDOlxcXFx4YW1vcHMtdWF0XFxcXFhhbU9wcy1OZXdcXFxcZnJvbnRlbmQtYXBwXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ZpbGVuYW1lID0gXCJDOlxcXFx4YW1vcHMtdWF0XFxcXFhhbU9wcy1OZXdcXFxcZnJvbnRlbmQtYXBwXFxcXHZpdGUuY29uZmlnLmpzXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ltcG9ydF9tZXRhX3VybCA9IFwiZmlsZTovLy9DOi94YW1vcHMtdWF0L1hhbU9wcy1OZXcvZnJvbnRlbmQtYXBwL3ZpdGUuY29uZmlnLmpzXCI7aW1wb3J0IHsgZGVmaW5lQ29uZmlnIH0gZnJvbSAndml0ZSc7XHJcbmltcG9ydCB7IHJlc29sdmUgfSBmcm9tICdwYXRoJztcclxuaW1wb3J0IGh0bWxJbmNsdWRlIGZyb20gJ3ZpdGUtcGx1Z2luLWh0bWwtaW5jbHVkZSc7XHJcblxyXG4vLyAtLS0gQ09ORklHVVJBVElPTiAtLS1cclxuLy8gRGVmaW5lIHRoZSBkZWZhdWx0IGF1dGggcG9ydCBhbmQgdGFyZ2V0XHJcbmNvbnN0IGF1dGhQb3J0ID0gcHJvY2Vzcy5lbnYuQVVUSF9QT1JUIHx8ICc4MDgwJztcclxuY29uc3QgYXV0aFRhcmdldCA9IGBodHRwOi8vbG9jYWxob3N0OiR7YXV0aFBvcnR9YDtcclxuXHJcbi8vIC0tLSBIRUxQRVIgRlVOQ1RJT04gLS0tXHJcbi8vIFRoaXMgZnVuY3Rpb24gcmV3cml0ZXMgdGhlIGJhY2tlbmQncyBjb29raWUgdG8gd29yayB3aXRoIHRoZSBmcm9udGVuZCBwcm94eVxyXG5jb25zdCBjb29raWVSZXdyaXRlID0gKHByb3h5UmVzLCByZXEpID0+IHtcclxuICBjb25zdCBjb29raWVzID0gcHJveHlSZXMuaGVhZGVyc1snc2V0LWNvb2tpZSddO1xyXG4gIGlmIChjb29raWVzKSB7XHJcbiAgICBjb25zdCBuZXdDb29raWVzID0gY29va2llcy5tYXAoY29va2llID0+XHJcbiAgICAgIGNvb2tpZVxyXG4gICAgICAgIC5yZXBsYWNlKC87IHBhdGg9XFwvLio/KDt8JCkvLCAnOyBwYXRoPS87JykgLy8gU2V0IFBhdGggdG8gcm9vdFxyXG4gICAgICAgIC5yZXBsYWNlKC87IGRvbWFpbj0uKj8oO3wkKS8sIGA7IGRvbWFpbj0ke3JlcS5oZWFkZXJzLmhvc3Quc3BsaXQoJzonKVswXX07YCkgLy8gU2V0IERvbWFpbiB0byBmcm9udGVuZFxyXG4gICAgKTtcclxuICAgIHByb3h5UmVzLmhlYWRlcnNbJ3NldC1jb29raWUnXSA9IG5ld0Nvb2tpZXM7XHJcbiAgfVxyXG59O1xyXG5cclxuZXhwb3J0IGRlZmF1bHQgZGVmaW5lQ29uZmlnKHtcclxuICBwbHVnaW5zOiBbXHJcbiAgICBodG1sSW5jbHVkZSgpLFxyXG4gIF0sXHJcblxyXG4gIHNlcnZlcjoge1xyXG4gICAgcG9ydDogNTE3MyxcclxuICAgIHByb3h5OiB7XHJcbiAgICAgIC8vIC0tLSAxLiBDT1JFIEFVVEhFTlRJQ0FUSU9OIC0tLVxyXG4gICAgICAnL2xvZ2luJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldCxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcclxuICAgICAgICBjb25maWd1cmU6IChwcm94eSwgb3B0aW9ucykgPT4ge1xyXG4gICAgICAgICAgcHJveHkub24oJ3Byb3h5UmVzJywgY29va2llUmV3cml0ZSk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9LFxyXG4gICAgICAnL2xvZ291dCc6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXHJcbiAgICAgICAgY29uZmlndXJlOiAocHJveHksIG9wdGlvbnMpID0+IHtcclxuICAgICAgICAgIHByb3h5Lm9uKCdwcm94eVJlcycsIGNvb2tpZVJld3JpdGUpO1xyXG4gICAgICAgIH1cclxuICAgICAgfSxcclxuXHJcbiAgICAgIC8vIC0tLSAyLiBVU0VSIFBST0ZJTEUgKEZpeGVzIFNpZGViYXIgVmlzaWJpbGl0eSkgLS0tXHJcbiAgICAgIC8vIEludGVyY2VwdHMgWGFtT3BzIHByb2ZpbGUgY2FsbCBhbmQgcmV3cml0ZXMgaXQgdG8gQmlsbE9wcyBmb3JtYXQgaWYgbmVlZGVkXHJcbiAgICAgICcvYXBpL3hhbW9wcy91c2VyL3Byb2ZpbGUnOiB7XHJcbiAgICAgICAgdGFyZ2V0OiBhdXRoVGFyZ2V0LFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlLFxyXG4gICAgICAgIHJld3JpdGU6IChwYXRoKSA9PiB7XHJcbiAgICAgICAgICBpZiAoYXV0aFBvcnQgPT09ICc4MDgyJykge1xyXG4gICAgICAgICAgICByZXR1cm4gcGF0aC5yZXBsYWNlKCcvYXBpL3hhbW9wcy91c2VyL3Byb2ZpbGUnLCAnL2FwaS9iaWxsb3BzL3Byb2ZpbGUnKTtcclxuICAgICAgICAgIH1cclxuICAgICAgICAgIHJldHVybiBwYXRoOyAvLyBLZWVwIGFzLWlzIGZvciBYYW1PcHNcclxuICAgICAgICB9XHJcbiAgICAgIH0sXHJcblxyXG4gICAgICAvLyAtLS0gMy4gU0hBUkVEIE1PRFVMRVMgLS0tXHJcbiAgICAgIC8vIFJvdXRlcyBBY2NvdW50IE1hbmFnZXIgcmVxdWVzdHMgdG8gdGhlIGFjdGl2ZSBiYWNrZW5kXHJcbiAgICAgICcvYXBpL3hhbW9wcy9hY2NvdW50LW1hbmFnZXInOiB7XHJcbiAgICAgICAgdGFyZ2V0OiBhdXRoVGFyZ2V0LFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlXHJcbiAgICAgIH0sXHJcbiAgICAgIC8vIFJvdXRlcyBEYXNoYm9hcmQgRGF0YSByZXF1ZXN0cyB0byB0aGUgYWN0aXZlIGJhY2tlbmQgKEZpeGVzIEVDT05OUkVGVVNFRClcclxuICAgICAgJy9hcGkveGFtb3BzL2Rhc2hib2FyZCc6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2VcclxuICAgICAgfSxcclxuXHJcbiAgICAgIC8vIC0tLSA0LiBDTE9VRCBQUk9WSURFUiBBUElzIC0tLVxyXG4gICAgICAvLyBUaGVzZSBmYWxsYmFja3MgcG9pbnQgdG8gJ2F1dGhUYXJnZXQnIHRvIGhhbmRsZSBjYXNlcyB3aGVyZSBjb2RlIHJlc2lkZXMgaW4gZGlmZmVyZW50IHNlcnZpY2VzXHJcbiAgICAgICcvYXBpL2F6dXJlJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LFxyXG4gICAgICAnL2FwaS9hd3MnOiB7IHRhcmdldDogYXV0aFRhcmdldCwgY2hhbmdlT3JpZ2luOiB0cnVlLCBzZWN1cmU6IGZhbHNlIH0sXHJcbiAgICAgICcvYXBpL2djcCc6IHsgdGFyZ2V0OiBhdXRoVGFyZ2V0LCBjaGFuZ2VPcmlnaW46IHRydWUsIHNlY3VyZTogZmFsc2UgfSxcclxuXHJcbiAgICAgIC8vIC0tLSA1LiBTUEVDSUZJQyBTRVJWSUNFIEVORFBPSU5UUyAtLS1cclxuICAgICAgLy8gQWRtaW4gJiBCaWxsb3BzIEFQSSByZXF1ZXN0cyBhbHdheXMgZ28gdG8gODA4MiAoYmlsbG9wcy1zZXJ2aWNlKVxyXG4gICAgICAnL2FwaS9hZG1pbic6IHtcclxuICAgICAgICB0YXJnZXQ6ICdodHRwOi8vbG9jYWxob3N0OjgwODInLFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlLFxyXG4gICAgICB9LFxyXG4gICAgICAnL2FwaS9iaWxsb3BzJzoge1xyXG4gICAgICAgIHRhcmdldDogJ2h0dHA6Ly9sb2NhbGhvc3Q6ODA4MicsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXHJcbiAgICAgIH0sXHJcbiAgICAgIC8vIEdlbmVyYWwgWGFtT3BzIHJlcXVlc3RzIChjYXRjaC1hbGwgZm9yIG90aGVyIHhhbW9wcyBlbmRwb2ludHMpXHJcbiAgICAgICcvYXBpL3hhbW9wcyc6IHtcclxuICAgICAgICB0YXJnZXQ6ICdodHRwOi8vbG9jYWxob3N0OjgwODAnLFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlXHJcbiAgICAgIH0sXHJcblxyXG4gICAgICAvLyBGZWF0dXJlIHNwZWNpZmljIGVuZHBvaW50c1xyXG4gICAgICAnL2FwaS9haS1hZHZpc29yJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LFxyXG4gICAgICAnL2FwaS9jaWNkJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LFxyXG5cclxuICAgICAgLy8gLS0tIDYuIFdFQlNPQ0tFVFMgLS0tXHJcbiAgICAgIC8vIEV4cGxpY2l0IHByb3h5IGZvciB0aGUgcmVuYW1lZCAvdGVybWluYWwgZW5kcG9pbnRcclxuICAgICAgJy90ZXJtaW5hbCc6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQucmVwbGFjZSgnaHR0cCcsICd3cycpLCAvLyBQcm94aWVzIHRvIHdzOi8vbG9jYWxob3N0OjgwODAvdGVybWluYWxcclxuICAgICAgICB3czogdHJ1ZSxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZVxyXG4gICAgICB9LFxyXG4gICAgICAvLyBHZW5lcmFsIFdlYlNvY2tldCBwcm94eVxyXG4gICAgICAnL3dzJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldC5yZXBsYWNlKCdodHRwJywgJ3dzJyksXHJcbiAgICAgICAgd3M6IHRydWUsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIGZvbGxvd1JlZGlyZWN0czogZmFsc2UsXHJcbiAgICAgICAgcmV3cml0ZVdzT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHRpbWVvdXQ6IDEwMDAwLFxyXG4gICAgICB9LFxyXG4gICAgfVxyXG4gIH0sXHJcblxyXG4gIGJ1aWxkOiB7XHJcbiAgICByb2xsdXBPcHRpb25zOiB7XHJcbiAgICAgIGlucHV0OiB7XHJcbiAgICAgICAgaW5kZXg6IHJlc29sdmUoX19kaXJuYW1lLCAnaW5kZXguaHRtbCcpLFxyXG4gICAgICAgICdhY2NvdW50LW1hbmFnZXInOiByZXNvbHZlKF9fZGlybmFtZSwgJ2FjY291bnQtbWFuYWdlci5odG1sJyksXHJcbiAgICAgICAgJ3VzZXItbWFuYWdlcic6IHJlc29sdmUoX19kaXJuYW1lLCAndXNlci1tYW5hZ2VyLmh0bWwnKSxcclxuICAgICAgICAnWGFtb3BzX1VzZXJfbWFuYWdlbWVudC5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdYYW1vcHNfVXNlcl9tYW5hZ2VtZW50Lmh0bWwnKSxcclxuICAgICAgICAnYWRkLWFjY291bnQnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2FkZC1hY2NvdW50Lmh0bWwnKSxcclxuICAgICAgICAnYWRkLWdjcC1hY2NvdW50JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhZGQtZ2NwLWFjY291bnQuaHRtbCcpLFxyXG4gICAgICAgIGFsZXJ0czogcmVzb2x2ZShfX2Rpcm5hbWUsICdhbGVydHMuaHRtbCcpLFxyXG4gICAgICAgIGNsb3VkazhzOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Nsb3VkazhzLmh0bWwnKSxcclxuICAgICAgICBjbG91ZGxpc3Q6IHJlc29sdmUoX19kaXJuYW1lLCAnY2xvdWRsaXN0Lmh0bWwnKSxcclxuICAgICAgICBjbG91ZG1hcDogcmVzb2x2ZShfX2Rpcm5hbWUsICdjbG91ZG1hcC5odG1sJyksXHJcbiAgICAgICAgY29zdDogcmVzb2x2ZShfX2Rpcm5hbWUsICdjb3N0Lmh0bWwnKSxcclxuICAgICAgICBkYXNoYm9hcmQ6IHJlc29sdmUoX19kaXJuYW1lLCAnZGFzaGJvYXJkLmh0bWwnKSxcclxuICAgICAgICAnZWtzLWRldGFpbHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Vrcy1kZXRhaWxzLmh0bWwnKSxcclxuICAgICAgICBmaW5vcHM6IHJlc29sdmUoX19kaXJuYW1lLCAnZmlub3BzLmh0bWwnKSxcclxuICAgICAgICByaWdodHNpemluZzogcmVzb2x2ZShfX2Rpcm5hbWUsICdyaWdodHNpemluZy5odG1sJyksXHJcbiAgICAgICAgcmVzZXJ2YXRpb246IHJlc29sdmUoX19kaXJuYW1lLCAncmVzZXJ2YXRpb24uaHRtbCcpLFxyXG4gICAgICAgIHdhc3RlOiByZXNvbHZlKF9fZGlybmFtZSwgJ3dhc3RlLmh0bWwnKSxcclxuICAgICAgICBwZXJmb3JtYW5jZTogcmVzb2x2ZShfX2Rpcm5hbWUsICdwZXJmb3JtYW5jZS5odG1sJyksXHJcbiAgICAgICAgeGFtb3BzX3RpY2tldHM6IHJlc29sdmUoX19kaXJuYW1lLCAneGFtb3BzX3RpY2tldHMuaHRtbCcpLFxyXG4gICAgICAgIGdjcF94YW1vcHNfdGlja2V0czogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfeGFtb3BzX3RpY2tldHMuaHRtbCcpLFxyXG4gICAgICAgIHhhbW9wc190aWNrZXRfZGV0YWlsOiByZXNvbHZlKF9fZGlybmFtZSwgJ3hhbW9wc190aWNrZXRfZGV0YWlsLmh0bWwnKSxcclxuICAgICAgICBnY3BfeGFtb3BzX3RpY2tldF9kZXRhaWw6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX3hhbW9wc190aWNrZXRfZGV0YWlsLmh0bWwnKSxcclxuICAgICAgICAnZ3JhZmFuYS1kYXNoYm9hcmQuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ3JhZmFuYS1kYXNoYm9hcmQuaHRtbCcpLFxyXG4gICAgICAgICdkZXZvcHNfaW5fdGhlX2JveC5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdkZXZvcHNfaW5fdGhlX2JveC5odG1sJyksXHJcbiAgICAgICAgJ2djcF9kZXZvcHNfaW5fdGhlX2JveC5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfZGV2b3BzX2luX3RoZV9ib3guaHRtbCcpLFxyXG4gICAgICAgICdjaWNkX3BpcGVsaW5lcy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdjaWNkX3BpcGVsaW5lcy5odG1sJyksXHJcbiAgICAgICAgc2VjdXJpdHk6IHJlc29sdmUoX19kaXJuYW1lLCAnc2VjdXJpdHkuaHRtbCcpLFxyXG4gICAgICAgICdhZGQtZ2l0aHViLWNvbmZpZy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhZGQtZ2l0aHViLWNvbmZpZy5odG1sJyksXHJcbiAgICAgICAgJ3NvbmFycXViZS5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdzb25hcnF1YmUuaHRtbCcpLFxyXG4gICAgICAgICdhaW9wcy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhaW9wcy5odG1sJyksXHJcbiAgICAgICAgJ2NvbXBsaWFuY2VvcHMuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnY29tcGxpYW5jZW9wcy5odG1sJyksXHJcbiAgICAgICAgJ2RhdGFvcHMuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZGF0YW9wcy5odG1sJyksXHJcbiAgICAgICAgJ2Nsb3Vkc2hlbGwuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnY2xvdWRzaGVsbC5odG1sJyksXHJcbiAgICAgICAgJ3Nwb3QtYXV0b21hdGlvbi5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdzcG90LWF1dG9tYXRpb24uaHRtbCcpLFxyXG5cclxuICAgICAgICAvLyBBZG1pbiBzdWJkaXIgZmlsZXNcclxuICAgICAgICAnYWRtaW5fY3JlZGl0cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl9jcmVkaXRzLmh0bWwnKSxcclxuICAgICAgICAnYWRtaW5faW52b2ljZV9kZXRhaWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYWRtaW5faW52b2ljZV9kZXRhaWwuaHRtbCcpLFxyXG4gICAgICAgICdhZG1pbl9pbnZvaWNlcyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl9pbnZvaWNlcy5odG1sJyksXHJcbiAgICAgICAgJ2FkbWluX3RpY2tldHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYWRtaW5fdGlja2V0cy5odG1sJyksXHJcbiAgICAgICAgJ2JpbGxpbmcnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYmlsbGluZy5odG1sJyksXHJcbiAgICAgICAgJ3RpY2tldHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvdGlja2V0cy5odG1sJyksXHJcbiAgICAgICAgJ3RpY2tldF9kZXRhaWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvdGlja2V0X2RldGFpbC5odG1sJyksXHJcbiAgICAgICAgJ2NyZWRpdHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvY3JlZGl0cy5odG1sJyksXHJcbiAgICAgICAgJ2ludm9pY2VzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2ludm9pY2VzLmh0bWwnKSxcclxuICAgICAgICAnYWRtaW5fY2xvdWRmcm9udF9iaWxsaW5nJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2FkbWluX2Nsb3VkZnJvbnRfYmlsbGluZy5odG1sJyksXHJcbiAgICAgICAgJ21hcmtldHBsYWNlLXB1cmNoYXNlcyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9tYXJrZXRwbGFjZS1wdXJjaGFzZXMuaHRtbCcpLFxyXG4gICAgICAgICd0aGlyZHBhcnR5LXRvb2xzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL3RoaXJkcGFydHktdG9vbHMuaHRtbCcpLFxyXG4gICAgICAgICd3b3Jrc3BhY2UtbGljZW5zZXMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvd29ya3NwYWNlLWxpY2Vuc2VzLmh0bWwnKSxcclxuXHJcbiAgICAgICAgLy8gR0NQIHN1YmRpciBmaWxlc1xyXG4gICAgICAgICdnY3BfY2xvdWRsaXN0JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfY2xvdWRsaXN0Lmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2Nsb3VkbWFwJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfY2xvdWRtYXAuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfY29zdCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX2Nvc3QuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfZGFzaGJvYXJkJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfZGFzaGJvYXJkLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2Zpbm9wcyc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX2Zpbm9wcy5odG1sJyksXHJcbiAgICAgICAgJ2djcC1iaWxsaW5nJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2djcC1iaWxsaW5nLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX3JpZ2h0c2l6aW5nJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfcmlnaHRzaXppbmcuaHRtbCcpLFxyXG4gICAgICAgICdnY3Bfc2VjdXJpdHknOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9zZWN1cml0eS5odG1sJyksXHJcbiAgICAgICAgJ2djcF93YXN0ZSc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX3dhc3RlLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2Nsb3VkazhzLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9jbG91ZGs4cy5odG1sJyksXHJcbiAgICAgICAgJ2djcF9wZXJmb3JtYW5jZS5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfcGVyZm9ybWFuY2UuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfcmVzZXJ2YXRpb25zLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9yZXNlcnZhdGlvbnMuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfYWxlcnRzLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9hbGVydHMuaHRtbCcpLFxyXG5cclxuICAgICAgICAvLyBBenVyZSBmaWxlc1xyXG4gICAgICAgICdhenVyZV9jbG91ZGxpc3QnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2F6dXJlX2Nsb3VkbGlzdC5odG1sJyksXHJcbiAgICAgICAgJ2F6dXJlX2Rhc2hib2FyZCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYXp1cmVfZGFzaGJvYXJkLmh0bWwnKSxcclxuICAgICAgICAnYXp1cmUtZmlub3BzLXJlcG9ydCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYXp1cmUtZmlub3BzLXJlcG9ydC5odG1sJylcclxuICAgICAgfSxcclxuICAgIH0sXHJcbiAgfSxcclxufSk7Il0sCiAgIm1hcHBpbmdzIjogIjtBQUF5UyxTQUFTLG9CQUFvQjtBQUN0VSxTQUFTLGVBQWU7QUFDeEIsT0FBTyxpQkFBaUI7QUFGeEIsSUFBTSxtQ0FBbUM7QUFNekMsSUFBTSxXQUFXLFFBQVEsSUFBSSxhQUFhO0FBQzFDLElBQU0sYUFBYSxvQkFBb0IsUUFBUTtBQUkvQyxJQUFNLGdCQUFnQixDQUFDLFVBQVUsUUFBUTtBQUN2QyxRQUFNLFVBQVUsU0FBUyxRQUFRLFlBQVk7QUFDN0MsTUFBSSxTQUFTO0FBQ1gsVUFBTSxhQUFhLFFBQVE7QUFBQSxNQUFJLFlBQzdCLE9BQ0csUUFBUSxxQkFBcUIsV0FBVyxFQUN4QyxRQUFRLHFCQUFxQixZQUFZLElBQUksUUFBUSxLQUFLLE1BQU0sR0FBRyxFQUFFLENBQUMsQ0FBQyxHQUFHO0FBQUE7QUFBQSxJQUMvRTtBQUNBLGFBQVMsUUFBUSxZQUFZLElBQUk7QUFBQSxFQUNuQztBQUNGO0FBRUEsSUFBTyxzQkFBUSxhQUFhO0FBQUEsRUFDMUIsU0FBUztBQUFBLElBQ1As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QjtBQUFBO0FBQUEsUUFHakUsaUJBQWlCLFFBQVEsa0NBQVcsNEJBQTRCO0FBQUEsUUFDaEUsd0JBQXdCLFFBQVEsa0NBQVcsbUNBQW1DO0FBQUEsUUFDOUUsa0JBQWtCLFFBQVEsa0NBQVcsNkJBQTZCO0FBQUEsUUFDbEUsaUJBQWlCLFFBQVEsa0NBQVcsNEJBQTRCO0FBQUEsUUFDaEUsV0FBVyxRQUFRLGtDQUFXLHNCQUFzQjtBQUFBLFFBQ3BELFdBQVcsUUFBUSxrQ0FBVyxzQkFBc0I7QUFBQSxRQUNwRCxpQkFBaUIsUUFBUSxrQ0FBVyw0QkFBNEI7QUFBQSxRQUNoRSxXQUFXLFFBQVEsa0NBQVcsc0JBQXNCO0FBQUEsUUFDcEQsWUFBWSxRQUFRLGtDQUFXLHVCQUF1QjtBQUFBLFFBQ3RELDRCQUE0QixRQUFRLGtDQUFXLHVDQUF1QztBQUFBLFFBQ3RGLHlCQUF5QixRQUFRLGtDQUFXLG9DQUFvQztBQUFBLFFBQ2hGLG9CQUFvQixRQUFRLGtDQUFXLCtCQUErQjtBQUFBLFFBQ3RFLHNCQUFzQixRQUFRLGtDQUFXLGlDQUFpQztBQUFBO0FBQUEsUUFHMUUsaUJBQWlCLFFBQVEsa0NBQVcsb0JBQW9CO0FBQUEsUUFDeEQsZ0JBQWdCLFFBQVEsa0NBQVcsbUJBQW1CO0FBQUEsUUFDdEQsWUFBWSxRQUFRLGtDQUFXLGVBQWU7QUFBQSxRQUM5QyxpQkFBaUIsUUFBUSxrQ0FBVyxvQkFBb0I7QUFBQSxRQUN4RCxjQUFjLFFBQVEsa0NBQVcsaUJBQWlCO0FBQUEsUUFDbEQsZUFBZSxRQUFRLGtDQUFXLDBCQUEwQjtBQUFBLFFBQzVELG1CQUFtQixRQUFRLGtDQUFXLHNCQUFzQjtBQUFBLFFBQzVELGdCQUFnQixRQUFRLGtDQUFXLG1CQUFtQjtBQUFBLFFBQ3RELGFBQWEsUUFBUSxrQ0FBVyxnQkFBZ0I7QUFBQSxRQUNoRCxxQkFBcUIsUUFBUSxrQ0FBVyxtQkFBbUI7QUFBQSxRQUMzRCx3QkFBd0IsUUFBUSxrQ0FBVyxzQkFBc0I7QUFBQSxRQUNqRSx5QkFBeUIsUUFBUSxrQ0FBVyx1QkFBdUI7QUFBQSxRQUNuRSxtQkFBbUIsUUFBUSxrQ0FBVyxpQkFBaUI7QUFBQTtBQUFBLFFBR3ZELG1CQUFtQixRQUFRLGtDQUFXLHNCQUFzQjtBQUFBLFFBQzVELG1CQUFtQixRQUFRLGtDQUFXLHNCQUFzQjtBQUFBLFFBQzVELHVCQUF1QixRQUFRLGtDQUFXLDBCQUEwQjtBQUFBLE1BQ3RFO0FBQUEsSUFDRjtBQUFBLEVBQ0Y7QUFDRixDQUFDOyIsCiAgIm5hbWVzIjogW10KfQo=
