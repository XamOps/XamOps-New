// vite.config.ts
import { defineConfig } from "file:///C:/Users/shiva/Desktop/20-01/XamOps-New/frontend-app/node_modules/vite/dist/node/index.js";
import react from "file:///C:/Users/shiva/Desktop/20-01/XamOps-New/frontend-app/node_modules/@vitejs/plugin-react/dist/index.js";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";
import htmlInclude from "file:///C:/Users/shiva/Desktop/20-01/XamOps-New/frontend-app/node_modules/vite-plugin-html-include/dist/index.js";
var __vite_injected_original_import_meta_url = "file:///C:/Users/shiva/Desktop/20-01/XamOps-New/frontend-app/vite.config.ts";
var __filename = fileURLToPath(__vite_injected_original_import_meta_url);
var __dirname = dirname(__filename);
var authPort = process.env.AUTH_PORT || "8080";
var authTarget = `http://localhost:${authPort}`;
var cookieRewrite = (proxyRes, _req) => {
  const cookies = proxyRes.headers["set-cookie"];
  if (cookies) {
    console.log("\u{1F36A} Original cookies from backend:", cookies);
    const newCookies = cookies.map((cookie) => {
      let rewrittenCookie = cookie.replace(/;\s*path=[^;]*/gi, "").replace(/;\s*domain=[^;]*/gi, "");
      rewrittenCookie += "; path=/";
      return rewrittenCookie;
    });
    console.log("\u{1F36A} Rewritten cookies for frontend:", newCookies);
    proxyRes.headers["set-cookie"] = newCookies;
  }
};
var vite_config_default = defineConfig({
  plugins: [
    react(),
    htmlInclude()
  ],
  css: {
    devSourcemap: false
  },
  // Define global for browser compatibility (fixes sockjs-client)
  define: {
    global: "globalThis",
    "process.env": {}
  },
  optimizeDeps: {
    esbuildOptions: {
      define: {
        global: "globalThis"
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      // --- 0. WEBSOCKET PROXY (Must be first) ---
      "/xamops-ws": {
        target: "http://localhost:8080",
        // Support SockJS (http + ws upgrade)
        ws: true,
        changeOrigin: true,
        secure: false
      },
      // Terminal WebSocket
      "/terminal": {
        target: "http://localhost:8080",
        ws: true,
        changeOrigin: true,
        secure: false
      },
      // --- 1. CORE AUTHENTICATION ---
      "/login": {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        configure: (proxy) => {
          proxy.on("proxyRes", cookieRewrite);
        }
      },
      "/logout": {
        target: authTarget,
        changeOrigin: true,
        secure: false,
        configure: (proxy) => {
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
      // CloudList API (needs longer timeout for large datasets)
      "/api/xamops/cloudlist": {
        target: "http://localhost:8080",
        changeOrigin: true,
        secure: false,
        timeout: 12e4,
        // 120s timeout for cloudlist
        proxyTimeout: 12e4
        // 120s proxy timeout
      },
      // General XamOps requests (catch-all for other xamops endpoints)
      "/api/xamops": {
        target: "http://localhost:8080",
        changeOrigin: true,
        secure: false,
        timeout: 6e4,
        // 60s timeout
        proxyTimeout: 6e4
        // 60s proxy timeout
      },
      // Feature specific endpoints
      "/api/ai-advisor": { target: authTarget, changeOrigin: true, secure: false },
      "/api/cicd": { target: authTarget, changeOrigin: true, secure: false },
      "/api/karpenter": { target: authTarget, changeOrigin: true, secure: false },
      "/api/ai": { target: authTarget, changeOrigin: true, secure: false }
      // ✅ Added missing proxy rule
    }
  },
  build: {
    rollupOptions: {
      input: {
        index: resolve(__dirname, "index.html"),
        "account-manager": resolve(__dirname, "account-manager.html"),
        "Xamops_User_management.html": resolve(__dirname, "Xamops_User_management.html"),
        "add-account": resolve(__dirname, "add-account.html"),
        "add-gcp-account": resolve(__dirname, "add-gcp-account.html"),
        alerts: resolve(__dirname, "alerts.html"),
        cloudk8s: resolve(__dirname, "cloudk8s.html"),
        cloudlist: resolve(__dirname, "cloudlist.html"),
        cloudmap: resolve(__dirname, "cloudmap.html"),
        cost: resolve(__dirname, "cost.html"),
        dashboard: resolve(__dirname, "dashboard.html"),
        "eks-details": resolve(__dirname, "eks-details.html"),
        finops: resolve(__dirname, "finops.html"),
        rightsizing: resolve(__dirname, "rightsizing.html"),
        reservation: resolve(__dirname, "reservation.html"),
        waste: resolve(__dirname, "waste.html"),
        performance: resolve(__dirname, "performance.html"),
        xamops_tickets: resolve(__dirname, "xamops_tickets.html"),
        gcp_xamops_tickets: resolve(__dirname, "gcp_xamops_tickets.html"),
        xamops_ticket_detail: resolve(__dirname, "xamops_ticket_detail.html"),
        gcp_xamops_ticket_detail: resolve(__dirname, "gcp_xamops_ticket_detail.html"),
        "grafana-dashboard.html": resolve(__dirname, "grafana-dashboard.html"),
        "devops_in_the_box.html": resolve(__dirname, "devops_in_the_box.html"),
        "gcp_devops_in_the_box.html": resolve(__dirname, "gcp_devops_in_the_box.html"),
        "cicd_pipelines.html": resolve(__dirname, "cicd_pipelines.html"),
        security: resolve(__dirname, "security.html"),
        "add-github-config.html": resolve(__dirname, "add-github-config.html"),
        "sonarqube.html": resolve(__dirname, "sonarqube.html"),
        "aiops.html": resolve(__dirname, "aiops.html"),
        "complianceops.html": resolve(__dirname, "complianceops.html"),
        "dataops.html": resolve(__dirname, "dataops.html"),
        "cloudshell.html": resolve(__dirname, "cloudshell.html"),
        "spot-automation.html": resolve(__dirname, "spot-automation.html"),
        "actions-history.html": resolve(__dirname, "actions-history.html"),
        "launch-analytics.html": resolve(__dirname, "launch-analytics.html"),
        "risp-coverage.html": resolve(__dirname, "risp-coverage.html"),
        // Admin subdir files
        "admin_credits": resolve(__dirname, "billops/admin_credits.html"),
        "admin_invoice_detail": resolve(__dirname, "billops/admin_invoice_detail.html"),
        "admin_invoices": resolve(__dirname, "billops/admin_invoices.html"),
        "admin_tickets": resolve(__dirname, "billops/admin_tickets.html"),
        "billing": resolve(__dirname, "billops/billing.html"),
        "tickets": resolve(__dirname, "billops/tickets.html"),
        "admin_agreements": resolve(__dirname, "billops/admin_agreements.html"),
        "agreements": resolve(__dirname, "billops/agreements.html"),
        "ticket_detail": resolve(__dirname, "billops/ticket_detail.html"),
        "credits": resolve(__dirname, "billops/credits.html"),
        "invoices": resolve(__dirname, "billops/invoices.html"),
        "dashboard_admin": resolve(__dirname, "billops/dashboard_admin.html"),
        "dashboard_user": resolve(__dirname, "billops/dashboard_user.html"),
        "admin_dashboard.html": resolve(__dirname, "billops/admin_dashboard.html"),
        "admin_cloudfront_billing": resolve(__dirname, "billops/admin_cloudfront_billing.html"),
        "marketplace-purchases": resolve(__dirname, "billops/marketplace-purchases.html"),
        "thirdparty-tools": resolve(__dirname, "billops/thirdparty-tools.html"),
        "workspace-licenses": resolve(__dirname, "billops/workspace-licenses.html"),
        "support-dashboard": resolve(__dirname, "billops/support-dashboard.html"),
        "unified-dashboard": resolve(__dirname, "unified-dashboard.html"),
        "cloudsitter.html": resolve(__dirname, "cloudsitter.html"),
        "calculator.html": resolve(__dirname, "calculator.html"),
        // GCP subdir files
        "gcp_cloudlist": resolve(__dirname, "gcp_cloudlist.html"),
        "gcp_cloudmap": resolve(__dirname, "gcp_cloudmap.html"),
        "gcp_cost": resolve(__dirname, "gcp_cost.html"),
        "gcp_dashboard": resolve(__dirname, "gcp_dashboard.html"),
        "gcp_finops": resolve(__dirname, "gcp_finops.html"),
        "gcp-billing": resolve(__dirname, "billops/gcp-billing.html"),
        "azure-billing": resolve(__dirname, "billops/azure-billing.html"),
        "billops-login": resolve(__dirname, "billops/login.html"),
        "gcp_rightsizing": resolve(__dirname, "gcp_rightsizing.html"),
        "gcp_security": resolve(__dirname, "gcp_security.html"),
        "gcp_waste": resolve(__dirname, "gcp_waste.html"),
        "gcp_cloudk8s.html": resolve(__dirname, "gcp_cloudk8s.html"),
        "gcp_performance.html": resolve(__dirname, "gcp_performance.html"),
        "gcp_reservations.html": resolve(__dirname, "gcp_reservations.html"),
        "gcp_alerts.html": resolve(__dirname, "gcp_alerts.html"),
        // Azure files
        "azure_cloudlist": resolve(__dirname, "azure_cloudlist.html"),
        "azure_dashboard": resolve(__dirname, "azure_dashboard.html"),
        "azure-finops-report": resolve(__dirname, "azure-finops-report.html"),
        "superadmin_customers": resolve(__dirname, "superadmin_customers.html")
        // Integration guide
      }
    }
  }
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcudHMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCJDOlxcXFxVc2Vyc1xcXFxzaGl2YVxcXFxEZXNrdG9wXFxcXDIwLTAxXFxcXFhhbU9wcy1OZXdcXFxcZnJvbnRlbmQtYXBwXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ZpbGVuYW1lID0gXCJDOlxcXFxVc2Vyc1xcXFxzaGl2YVxcXFxEZXNrdG9wXFxcXDIwLTAxXFxcXFhhbU9wcy1OZXdcXFxcZnJvbnRlbmQtYXBwXFxcXHZpdGUuY29uZmlnLnRzXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ltcG9ydF9tZXRhX3VybCA9IFwiZmlsZTovLy9DOi9Vc2Vycy9zaGl2YS9EZXNrdG9wLzIwLTAxL1hhbU9wcy1OZXcvZnJvbnRlbmQtYXBwL3ZpdGUuY29uZmlnLnRzXCI7aW1wb3J0IHsgZGVmaW5lQ29uZmlnIH0gZnJvbSAndml0ZSc7XHJcbmltcG9ydCByZWFjdCBmcm9tICdAdml0ZWpzL3BsdWdpbi1yZWFjdCc7XHJcbmltcG9ydCB7IHJlc29sdmUsIGRpcm5hbWUgfSBmcm9tICdwYXRoJztcclxuaW1wb3J0IHsgZmlsZVVSTFRvUGF0aCB9IGZyb20gJ3VybCc7XHJcbmltcG9ydCBodG1sSW5jbHVkZSBmcm9tICd2aXRlLXBsdWdpbi1odG1sLWluY2x1ZGUnO1xyXG5cclxuY29uc3QgX19maWxlbmFtZSA9IGZpbGVVUkxUb1BhdGgoaW1wb3J0Lm1ldGEudXJsKTtcclxuY29uc3QgX19kaXJuYW1lID0gZGlybmFtZShfX2ZpbGVuYW1lKTtcclxuXHJcbi8vIC0tLSBDT05GSUdVUkFUSU9OIC0tLVxyXG4vLyBEZWZpbmUgdGhlIGRlZmF1bHQgYXV0aCBwb3J0IGFuZCB0YXJnZXRcclxuY29uc3QgYXV0aFBvcnQgPSBwcm9jZXNzLmVudi5BVVRIX1BPUlQgfHwgJzgwODAnO1xyXG5jb25zdCBhdXRoVGFyZ2V0ID0gYGh0dHA6Ly9sb2NhbGhvc3Q6JHthdXRoUG9ydH1gO1xyXG5cclxuLy8gLS0tIEhFTFBFUiBGVU5DVElPTiAtLS1cclxuLy8gVGhpcyBmdW5jdGlvbiByZXdyaXRlcyB0aGUgYmFja2VuZCdzIGNvb2tpZSB0byB3b3JrIHdpdGggdGhlIGZyb250ZW5kIHByb3h5XHJcbmNvbnN0IGNvb2tpZVJld3JpdGUgPSAocHJveHlSZXM6IGFueSwgX3JlcTogYW55KSA9PiB7XHJcbiAgY29uc3QgY29va2llcyA9IHByb3h5UmVzLmhlYWRlcnNbJ3NldC1jb29raWUnXTtcclxuICBpZiAoY29va2llcykge1xyXG4gICAgY29uc29sZS5sb2coJ1x1RDgzQ1x1REY2QSBPcmlnaW5hbCBjb29raWVzIGZyb20gYmFja2VuZDonLCBjb29raWVzKTtcclxuICAgIGNvbnN0IG5ld0Nvb2tpZXMgPSBjb29raWVzLm1hcCgoY29va2llOiBzdHJpbmcpID0+IHtcclxuICAgICAgLy8gUmVtb3ZlIGRvbWFpbiByZXN0cmljdGlvbnMgYW5kIGVuc3VyZSBwYXRoIGlzIHJvb3RcclxuICAgICAgbGV0IHJld3JpdHRlbkNvb2tpZSA9IGNvb2tpZVxyXG4gICAgICAgIC5yZXBsYWNlKC87XFxzKnBhdGg9W147XSovZ2ksICcnKSAvLyBSZW1vdmUgZXhpc3RpbmcgcGF0aFxyXG4gICAgICAgIC5yZXBsYWNlKC87XFxzKmRvbWFpbj1bXjtdKi9naSwgJycpOyAvLyBSZW1vdmUgZXhpc3RpbmcgZG9tYWluXHJcblxyXG4gICAgICAvLyBBZGQgcGF0aD0vIGF0IHRoZSBlbmRcclxuICAgICAgcmV3cml0dGVuQ29va2llICs9ICc7IHBhdGg9Lyc7XHJcblxyXG4gICAgICByZXR1cm4gcmV3cml0dGVuQ29va2llO1xyXG4gICAgfSk7XHJcbiAgICBjb25zb2xlLmxvZygnXHVEODNDXHVERjZBIFJld3JpdHRlbiBjb29raWVzIGZvciBmcm9udGVuZDonLCBuZXdDb29raWVzKTtcclxuICAgIHByb3h5UmVzLmhlYWRlcnNbJ3NldC1jb29raWUnXSA9IG5ld0Nvb2tpZXM7XHJcbiAgfVxyXG59O1xyXG5cclxuZXhwb3J0IGRlZmF1bHQgZGVmaW5lQ29uZmlnKHtcclxuICBwbHVnaW5zOiBbXHJcbiAgICByZWFjdCgpLFxyXG4gICAgaHRtbEluY2x1ZGUoKSxcclxuICBdLFxyXG5cclxuICBjc3M6IHtcclxuICAgIGRldlNvdXJjZW1hcDogZmFsc2UsXHJcbiAgfSxcclxuXHJcbiAgLy8gRGVmaW5lIGdsb2JhbCBmb3IgYnJvd3NlciBjb21wYXRpYmlsaXR5IChmaXhlcyBzb2NranMtY2xpZW50KVxyXG4gIGRlZmluZToge1xyXG4gICAgZ2xvYmFsOiAnZ2xvYmFsVGhpcycsXHJcbiAgICAncHJvY2Vzcy5lbnYnOiB7fSxcclxuICB9LFxyXG5cclxuICBvcHRpbWl6ZURlcHM6IHtcclxuICAgIGVzYnVpbGRPcHRpb25zOiB7XHJcbiAgICAgIGRlZmluZToge1xyXG4gICAgICAgIGdsb2JhbDogJ2dsb2JhbFRoaXMnLFxyXG4gICAgICB9LFxyXG4gICAgfSxcclxuICB9LFxyXG5cclxuICBzZXJ2ZXI6IHtcclxuICAgIHBvcnQ6IDUxNzMsXHJcbiAgICBwcm94eToge1xyXG4gICAgICAvLyAtLS0gMC4gV0VCU09DS0VUIFBST1hZIChNdXN0IGJlIGZpcnN0KSAtLS1cclxuICAgICAgJy94YW1vcHMtd3MnOiB7XHJcbiAgICAgICAgdGFyZ2V0OiAnaHR0cDovL2xvY2FsaG9zdDo4MDgwJywgLy8gU3VwcG9ydCBTb2NrSlMgKGh0dHAgKyB3cyB1cGdyYWRlKVxyXG4gICAgICAgIHdzOiB0cnVlLFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlLFxyXG4gICAgICB9LFxyXG4gICAgICAvLyBUZXJtaW5hbCBXZWJTb2NrZXRcclxuICAgICAgJy90ZXJtaW5hbCc6IHtcclxuICAgICAgICB0YXJnZXQ6ICdodHRwOi8vbG9jYWxob3N0OjgwODAnLFxyXG4gICAgICAgIHdzOiB0cnVlLFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlLFxyXG4gICAgICB9LFxyXG4gICAgICAvLyAtLS0gMS4gQ09SRSBBVVRIRU5USUNBVElPTiAtLS1cclxuICAgICAgJy9sb2dpbic6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXHJcbiAgICAgICAgY29uZmlndXJlOiAocHJveHk6IGFueSkgPT4ge1xyXG4gICAgICAgICAgcHJveHkub24oJ3Byb3h5UmVzJywgY29va2llUmV3cml0ZSk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9LFxyXG4gICAgICAnL2xvZ291dCc6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXHJcbiAgICAgICAgY29uZmlndXJlOiAocHJveHk6IGFueSkgPT4ge1xyXG4gICAgICAgICAgcHJveHkub24oJ3Byb3h5UmVzJywgY29va2llUmV3cml0ZSk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9LFxyXG4gICAgICAvLyBcdTI3MDUgQURERUQ6IEJyaWRnZSBmb3IgUGFzc3dvcmQgUmVzZXQgRmVhdHVyZVxyXG4gICAgICAnL2FwaS9hdXRoJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldCxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcclxuICAgICAgfSxcclxuXHJcbiAgICAgIC8vIC0tLSAyLiBVU0VSIFBST0ZJTEUgKEZpeGVzIFNpZGViYXIgVmlzaWJpbGl0eSkgLS0tXHJcbiAgICAgIC8vIEludGVyY2VwdHMgWGFtT3BzIHByb2ZpbGUgY2FsbCBhbmQgcmV3cml0ZXMgaXQgdG8gQmlsbE9wcyBmb3JtYXQgaWYgbmVlZGVkXHJcbiAgICAgICcvYXBpL3hhbW9wcy91c2VyL3Byb2ZpbGUnOiB7XHJcbiAgICAgICAgdGFyZ2V0OiBhdXRoVGFyZ2V0LFxyXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcclxuICAgICAgICBzZWN1cmU6IGZhbHNlLFxyXG4gICAgICAgIHJld3JpdGU6IChwYXRoOiBzdHJpbmcpID0+IHtcclxuICAgICAgICAgIGlmIChhdXRoUG9ydCA9PT0gJzgwODInKSB7XHJcbiAgICAgICAgICAgIHJldHVybiBwYXRoLnJlcGxhY2UoJy9hcGkveGFtb3BzL3VzZXIvcHJvZmlsZScsICcvYXBpL2JpbGxvcHMvcHJvZmlsZScpO1xyXG4gICAgICAgICAgfVxyXG4gICAgICAgICAgcmV0dXJuIHBhdGg7IC8vIEtlZXAgYXMtaXMgZm9yIFhhbU9wc1xyXG4gICAgICAgIH1cclxuICAgICAgfSxcclxuXHJcbiAgICAgIC8vIC0tLSAzLiBTSEFSRUQgTU9EVUxFUyAtLS1cclxuICAgICAgLy8gUm91dGVzIEFjY291bnQgTWFuYWdlciByZXF1ZXN0cyB0byB0aGUgYWN0aXZlIGJhY2tlbmRcclxuICAgICAgJy9hcGkveGFtb3BzL2FjY291bnQtbWFuYWdlcic6IHtcclxuICAgICAgICB0YXJnZXQ6IGF1dGhUYXJnZXQsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2VcclxuICAgICAgfSxcclxuICAgICAgLy8gUm91dGVzIERhc2hib2FyZCBEYXRhIHJlcXVlc3RzIHRvIHRoZSBhY3RpdmUgYmFja2VuZCAoRml4ZXMgRUNPTk5SRUZVU0VEKVxyXG4gICAgICAnL2FwaS94YW1vcHMvZGFzaGJvYXJkJzoge1xyXG4gICAgICAgIHRhcmdldDogYXV0aFRhcmdldCxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZVxyXG4gICAgICB9LFxyXG5cclxuICAgICAgLy8gLS0tIDQuIENMT1VEIFBST1ZJREVSIEFQSXMgLS0tXHJcbiAgICAgIC8vIFRoZXNlIGZhbGxiYWNrcyBwb2ludCB0byAnYXV0aFRhcmdldCcgdG8gaGFuZGxlIGNhc2VzIHdoZXJlIGNvZGUgcmVzaWRlcyBpbiBkaWZmZXJlbnQgc2VydmljZXNcclxuICAgICAgJy9hcGkvYXp1cmUnOiB7IHRhcmdldDogYXV0aFRhcmdldCwgY2hhbmdlT3JpZ2luOiB0cnVlLCBzZWN1cmU6IGZhbHNlIH0sXHJcbiAgICAgICcvYXBpL2F3cyc6IHsgdGFyZ2V0OiBhdXRoVGFyZ2V0LCBjaGFuZ2VPcmlnaW46IHRydWUsIHNlY3VyZTogZmFsc2UgfSxcclxuICAgICAgJy9hcGkvZ2NwJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LFxyXG5cclxuICAgICAgLy8gLS0tIDUuIFNQRUNJRklDIFNFUlZJQ0UgRU5EUE9JTlRTIC0tLVxyXG4gICAgICAvLyBBZG1pbiAmIEJpbGxvcHMgQVBJIHJlcXVlc3RzIGFsd2F5cyBnbyB0byA4MDgyIChiaWxsb3BzLXNlcnZpY2UpXHJcbiAgICAgICcvYXBpL2FkbWluJzoge1xyXG4gICAgICAgIHRhcmdldDogJ2h0dHA6Ly9sb2NhbGhvc3Q6ODA4MicsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXHJcbiAgICAgIH0sXHJcbiAgICAgICcvYXBpL2JpbGxvcHMnOiB7XHJcbiAgICAgICAgdGFyZ2V0OiAnaHR0cDovL2xvY2FsaG9zdDo4MDgyJyxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcclxuICAgICAgfSxcclxuICAgICAgLy8gQ2xvdWRMaXN0IEFQSSAobmVlZHMgbG9uZ2VyIHRpbWVvdXQgZm9yIGxhcmdlIGRhdGFzZXRzKVxyXG4gICAgICAnL2FwaS94YW1vcHMvY2xvdWRsaXN0Jzoge1xyXG4gICAgICAgIHRhcmdldDogJ2h0dHA6Ly9sb2NhbGhvc3Q6ODA4MCcsXHJcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxyXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXHJcbiAgICAgICAgdGltZW91dDogMTIwMDAwLCAvLyAxMjBzIHRpbWVvdXQgZm9yIGNsb3VkbGlzdFxyXG4gICAgICAgIHByb3h5VGltZW91dDogMTIwMDAwIC8vIDEyMHMgcHJveHkgdGltZW91dFxyXG4gICAgICB9LFxyXG4gICAgICAvLyBHZW5lcmFsIFhhbU9wcyByZXF1ZXN0cyAoY2F0Y2gtYWxsIGZvciBvdGhlciB4YW1vcHMgZW5kcG9pbnRzKVxyXG4gICAgICAnL2FwaS94YW1vcHMnOiB7XHJcbiAgICAgICAgdGFyZ2V0OiAnaHR0cDovL2xvY2FsaG9zdDo4MDgwJyxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcclxuICAgICAgICB0aW1lb3V0OiA2MDAwMCwgLy8gNjBzIHRpbWVvdXRcclxuICAgICAgICBwcm94eVRpbWVvdXQ6IDYwMDAwIC8vIDYwcyBwcm94eSB0aW1lb3V0XHJcbiAgICAgIH0sXHJcblxyXG4gICAgICAvLyBGZWF0dXJlIHNwZWNpZmljIGVuZHBvaW50c1xyXG4gICAgICAnL2FwaS9haS1hZHZpc29yJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LFxyXG4gICAgICAnL2FwaS9jaWNkJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LFxyXG4gICAgICAnL2FwaS9rYXJwZW50ZXInOiB7IHRhcmdldDogYXV0aFRhcmdldCwgY2hhbmdlT3JpZ2luOiB0cnVlLCBzZWN1cmU6IGZhbHNlIH0sXHJcbiAgICAgICcvYXBpL2FpJzogeyB0YXJnZXQ6IGF1dGhUYXJnZXQsIGNoYW5nZU9yaWdpbjogdHJ1ZSwgc2VjdXJlOiBmYWxzZSB9LCAvLyBcdTI3MDUgQWRkZWQgbWlzc2luZyBwcm94eSBydWxlXHJcblxyXG5cclxuICAgIH1cclxuICB9LFxyXG5cclxuICBidWlsZDoge1xyXG4gICAgcm9sbHVwT3B0aW9uczoge1xyXG4gICAgICBpbnB1dDoge1xyXG4gICAgICAgIGluZGV4OiByZXNvbHZlKF9fZGlybmFtZSwgJ2luZGV4Lmh0bWwnKSxcclxuICAgICAgICAnYWNjb3VudC1tYW5hZ2VyJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhY2NvdW50LW1hbmFnZXIuaHRtbCcpLFxyXG4gICAgICAgICdYYW1vcHNfVXNlcl9tYW5hZ2VtZW50Lmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ1hhbW9wc19Vc2VyX21hbmFnZW1lbnQuaHRtbCcpLFxyXG4gICAgICAgICdhZGQtYWNjb3VudCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYWRkLWFjY291bnQuaHRtbCcpLFxyXG4gICAgICAgICdhZGQtZ2NwLWFjY291bnQnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2FkZC1nY3AtYWNjb3VudC5odG1sJyksXHJcbiAgICAgICAgYWxlcnRzOiByZXNvbHZlKF9fZGlybmFtZSwgJ2FsZXJ0cy5odG1sJyksXHJcbiAgICAgICAgY2xvdWRrOHM6IHJlc29sdmUoX19kaXJuYW1lLCAnY2xvdWRrOHMuaHRtbCcpLFxyXG4gICAgICAgIGNsb3VkbGlzdDogcmVzb2x2ZShfX2Rpcm5hbWUsICdjbG91ZGxpc3QuaHRtbCcpLFxyXG4gICAgICAgIGNsb3VkbWFwOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Nsb3VkbWFwLmh0bWwnKSxcclxuICAgICAgICBjb3N0OiByZXNvbHZlKF9fZGlybmFtZSwgJ2Nvc3QuaHRtbCcpLFxyXG4gICAgICAgIGRhc2hib2FyZDogcmVzb2x2ZShfX2Rpcm5hbWUsICdkYXNoYm9hcmQuaHRtbCcpLFxyXG4gICAgICAgICdla3MtZGV0YWlscyc6IHJlc29sdmUoX19kaXJuYW1lLCAnZWtzLWRldGFpbHMuaHRtbCcpLFxyXG4gICAgICAgIGZpbm9wczogcmVzb2x2ZShfX2Rpcm5hbWUsICdmaW5vcHMuaHRtbCcpLFxyXG4gICAgICAgIHJpZ2h0c2l6aW5nOiByZXNvbHZlKF9fZGlybmFtZSwgJ3JpZ2h0c2l6aW5nLmh0bWwnKSxcclxuICAgICAgICByZXNlcnZhdGlvbjogcmVzb2x2ZShfX2Rpcm5hbWUsICdyZXNlcnZhdGlvbi5odG1sJyksXHJcbiAgICAgICAgd2FzdGU6IHJlc29sdmUoX19kaXJuYW1lLCAnd2FzdGUuaHRtbCcpLFxyXG4gICAgICAgIHBlcmZvcm1hbmNlOiByZXNvbHZlKF9fZGlybmFtZSwgJ3BlcmZvcm1hbmNlLmh0bWwnKSxcclxuICAgICAgICB4YW1vcHNfdGlja2V0czogcmVzb2x2ZShfX2Rpcm5hbWUsICd4YW1vcHNfdGlja2V0cy5odG1sJyksXHJcbiAgICAgICAgZ2NwX3hhbW9wc190aWNrZXRzOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF94YW1vcHNfdGlja2V0cy5odG1sJyksXHJcbiAgICAgICAgeGFtb3BzX3RpY2tldF9kZXRhaWw6IHJlc29sdmUoX19kaXJuYW1lLCAneGFtb3BzX3RpY2tldF9kZXRhaWwuaHRtbCcpLFxyXG4gICAgICAgIGdjcF94YW1vcHNfdGlja2V0X2RldGFpbDogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfeGFtb3BzX3RpY2tldF9kZXRhaWwuaHRtbCcpLFxyXG4gICAgICAgICdncmFmYW5hLWRhc2hib2FyZC5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdncmFmYW5hLWRhc2hib2FyZC5odG1sJyksXHJcbiAgICAgICAgJ2Rldm9wc19pbl90aGVfYm94Lmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Rldm9wc19pbl90aGVfYm94Lmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2Rldm9wc19pbl90aGVfYm94Lmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF9kZXZvcHNfaW5fdGhlX2JveC5odG1sJyksXHJcbiAgICAgICAgJ2NpY2RfcGlwZWxpbmVzLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2NpY2RfcGlwZWxpbmVzLmh0bWwnKSxcclxuICAgICAgICBzZWN1cml0eTogcmVzb2x2ZShfX2Rpcm5hbWUsICdzZWN1cml0eS5odG1sJyksXHJcbiAgICAgICAgJ2FkZC1naXRodWItY29uZmlnLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2FkZC1naXRodWItY29uZmlnLmh0bWwnKSxcclxuICAgICAgICAnc29uYXJxdWJlLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ3NvbmFycXViZS5odG1sJyksXHJcbiAgICAgICAgJ2Fpb3BzLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Fpb3BzLmh0bWwnKSxcclxuICAgICAgICAnY29tcGxpYW5jZW9wcy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdjb21wbGlhbmNlb3BzLmh0bWwnKSxcclxuICAgICAgICAnZGF0YW9wcy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdkYXRhb3BzLmh0bWwnKSxcclxuICAgICAgICAnY2xvdWRzaGVsbC5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdjbG91ZHNoZWxsLmh0bWwnKSxcclxuICAgICAgICAnc3BvdC1hdXRvbWF0aW9uLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ3Nwb3QtYXV0b21hdGlvbi5odG1sJyksXHJcbiAgICAgICAgJ2FjdGlvbnMtaGlzdG9yeS5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhY3Rpb25zLWhpc3RvcnkuaHRtbCcpLFxyXG4gICAgICAgICdsYXVuY2gtYW5hbHl0aWNzLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2xhdW5jaC1hbmFseXRpY3MuaHRtbCcpLFxyXG4gICAgICAgICdyaXNwLWNvdmVyYWdlLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ3Jpc3AtY292ZXJhZ2UuaHRtbCcpLFxyXG4gICAgICAgIC8vIEFkbWluIHN1YmRpciBmaWxlc1xyXG4gICAgICAgICdhZG1pbl9jcmVkaXRzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2FkbWluX2NyZWRpdHMuaHRtbCcpLFxyXG4gICAgICAgICdhZG1pbl9pbnZvaWNlX2RldGFpbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl9pbnZvaWNlX2RldGFpbC5odG1sJyksXHJcbiAgICAgICAgJ2FkbWluX2ludm9pY2VzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2FkbWluX2ludm9pY2VzLmh0bWwnKSxcclxuICAgICAgICAnYWRtaW5fdGlja2V0cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl90aWNrZXRzLmh0bWwnKSxcclxuICAgICAgICAnYmlsbGluZyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9iaWxsaW5nLmh0bWwnKSxcclxuICAgICAgICAndGlja2V0cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy90aWNrZXRzLmh0bWwnKSxcclxuICAgICAgICAnYWRtaW5fYWdyZWVtZW50cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl9hZ3JlZW1lbnRzLmh0bWwnKSxcclxuICAgICAgICAnYWdyZWVtZW50cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZ3JlZW1lbnRzLmh0bWwnKSxcclxuICAgICAgICAndGlja2V0X2RldGFpbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy90aWNrZXRfZGV0YWlsLmh0bWwnKSxcclxuICAgICAgICAnY3JlZGl0cyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9jcmVkaXRzLmh0bWwnKSxcclxuICAgICAgICAnaW52b2ljZXMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvaW52b2ljZXMuaHRtbCcpLFxyXG4gICAgICAgICdkYXNoYm9hcmRfYWRtaW4nOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvZGFzaGJvYXJkX2FkbWluLmh0bWwnKSxcclxuICAgICAgICAnZGFzaGJvYXJkX3VzZXInOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvZGFzaGJvYXJkX3VzZXIuaHRtbCcpLFxyXG4gICAgICAgICdhZG1pbl9kYXNoYm9hcmQuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9hZG1pbl9kYXNoYm9hcmQuaHRtbCcpLFxyXG4gICAgICAgICdhZG1pbl9jbG91ZGZyb250X2JpbGxpbmcnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvYWRtaW5fY2xvdWRmcm9udF9iaWxsaW5nLmh0bWwnKSxcclxuICAgICAgICAnbWFya2V0cGxhY2UtcHVyY2hhc2VzJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL21hcmtldHBsYWNlLXB1cmNoYXNlcy5odG1sJyksXHJcbiAgICAgICAgJ3RoaXJkcGFydHktdG9vbHMnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2JpbGxvcHMvdGhpcmRwYXJ0eS10b29scy5odG1sJyksXHJcbiAgICAgICAgJ3dvcmtzcGFjZS1saWNlbnNlcyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy93b3Jrc3BhY2UtbGljZW5zZXMuaHRtbCcpLFxyXG4gICAgICAgICdzdXBwb3J0LWRhc2hib2FyZCc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9zdXBwb3J0LWRhc2hib2FyZC5odG1sJyksXHJcbiAgICAgICAgJ3VuaWZpZWQtZGFzaGJvYXJkJzogcmVzb2x2ZShfX2Rpcm5hbWUsICd1bmlmaWVkLWRhc2hib2FyZC5odG1sJyksXHJcbiAgICAgICAgJ2Nsb3Vkc2l0dGVyLmh0bWwnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2Nsb3Vkc2l0dGVyLmh0bWwnKSxcclxuICAgICAgICAnY2FsY3VsYXRvci5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdjYWxjdWxhdG9yLmh0bWwnKSxcclxuXHJcbiAgICAgICAgLy8gR0NQIHN1YmRpciBmaWxlc1xyXG4gICAgICAgICdnY3BfY2xvdWRsaXN0JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfY2xvdWRsaXN0Lmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2Nsb3VkbWFwJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfY2xvdWRtYXAuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfY29zdCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX2Nvc3QuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfZGFzaGJvYXJkJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfZGFzaGJvYXJkLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2Zpbm9wcyc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX2Zpbm9wcy5odG1sJyksXHJcbiAgICAgICAgJ2djcC1iaWxsaW5nJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdiaWxsb3BzL2djcC1iaWxsaW5nLmh0bWwnKSxcclxuICAgICAgICAnYXp1cmUtYmlsbGluZyc6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9henVyZS1iaWxsaW5nLmh0bWwnKSxcclxuICAgICAgICAnYmlsbG9wcy1sb2dpbic6IHJlc29sdmUoX19kaXJuYW1lLCAnYmlsbG9wcy9sb2dpbi5odG1sJyksXHJcbiAgICAgICAgJ2djcF9yaWdodHNpemluZyc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX3JpZ2h0c2l6aW5nLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX3NlY3VyaXR5JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3Bfc2VjdXJpdHkuaHRtbCcpLFxyXG4gICAgICAgICdnY3Bfd2FzdGUnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2djcF93YXN0ZS5odG1sJyksXHJcbiAgICAgICAgJ2djcF9jbG91ZGs4cy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfY2xvdWRrOHMuaHRtbCcpLFxyXG4gICAgICAgICdnY3BfcGVyZm9ybWFuY2UuaHRtbCc6IHJlc29sdmUoX19kaXJuYW1lLCAnZ2NwX3BlcmZvcm1hbmNlLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX3Jlc2VydmF0aW9ucy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfcmVzZXJ2YXRpb25zLmh0bWwnKSxcclxuICAgICAgICAnZ2NwX2FsZXJ0cy5odG1sJzogcmVzb2x2ZShfX2Rpcm5hbWUsICdnY3BfYWxlcnRzLmh0bWwnKSxcclxuXHJcbiAgICAgICAgLy8gQXp1cmUgZmlsZXNcclxuICAgICAgICAnYXp1cmVfY2xvdWRsaXN0JzogcmVzb2x2ZShfX2Rpcm5hbWUsICdhenVyZV9jbG91ZGxpc3QuaHRtbCcpLFxyXG4gICAgICAgICdhenVyZV9kYXNoYm9hcmQnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2F6dXJlX2Rhc2hib2FyZC5odG1sJyksXHJcbiAgICAgICAgJ2F6dXJlLWZpbm9wcy1yZXBvcnQnOiByZXNvbHZlKF9fZGlybmFtZSwgJ2F6dXJlLWZpbm9wcy1yZXBvcnQuaHRtbCcpLFxyXG4gICAgICAgICdzdXBlcmFkbWluX2N1c3RvbWVycyc6IHJlc29sdmUoX19kaXJuYW1lLCAnc3VwZXJhZG1pbl9jdXN0b21lcnMuaHRtbCcpLFxyXG5cclxuICAgICAgICAvLyBJbnRlZ3JhdGlvbiBndWlkZVxyXG4gICAgICB9LFxyXG4gICAgfSxcclxuICB9LFxyXG59KTtcclxuIl0sCiAgIm1hcHBpbmdzIjogIjtBQUE0VixTQUFTLG9CQUFvQjtBQUN6WCxPQUFPLFdBQVc7QUFDbEIsU0FBUyxTQUFTLGVBQWU7QUFDakMsU0FBUyxxQkFBcUI7QUFDOUIsT0FBTyxpQkFBaUI7QUFKcU0sSUFBTSwyQ0FBMkM7QUFNOVEsSUFBTSxhQUFhLGNBQWMsd0NBQWU7QUFDaEQsSUFBTSxZQUFZLFFBQVEsVUFBVTtBQUlwQyxJQUFNLFdBQVcsUUFBUSxJQUFJLGFBQWE7QUFDMUMsSUFBTSxhQUFhLG9CQUFvQixRQUFRO0FBSS9DLElBQU0sZ0JBQWdCLENBQUMsVUFBZSxTQUFjO0FBQ2xELFFBQU0sVUFBVSxTQUFTLFFBQVEsWUFBWTtBQUM3QyxNQUFJLFNBQVM7QUFDWCxZQUFRLElBQUksNENBQXFDLE9BQU87QUFDeEQsVUFBTSxhQUFhLFFBQVEsSUFBSSxDQUFDLFdBQW1CO0FBRWpELFVBQUksa0JBQWtCLE9BQ25CLFFBQVEsb0JBQW9CLEVBQUUsRUFDOUIsUUFBUSxzQkFBc0IsRUFBRTtBQUduQyx5QkFBbUI7QUFFbkIsYUFBTztBQUFBLElBQ1QsQ0FBQztBQUNELFlBQVEsSUFBSSw2Q0FBc0MsVUFBVTtBQUM1RCxhQUFTLFFBQVEsWUFBWSxJQUFJO0FBQUEsRUFDbkM7QUFDRjtBQUVBLElBQU8sc0JBQVEsYUFBYTtBQUFBLEVBQzFCLFNBQVM7QUFBQSxJQUNQLE1BQU07QUFBQSxJQUNOLFlBQVk7QUFBQSxFQUNkO0FBQUEsRUFFQSxLQUFLO0FBQUEsSUFDSCxjQUFjO0FBQUEsRUFDaEI7QUFBQTtBQUFBLEVBR0EsUUFBUTtBQUFBLElBQ04sUUFBUTtBQUFBLElBQ1IsZUFBZSxDQUFDO0FBQUEsRUFDbEI7QUFBQSxFQUVBLGNBQWM7QUFBQSxJQUNaLGdCQUFnQjtBQUFBLE1BQ2QsUUFBUTtBQUFBLFFBQ04sUUFBUTtBQUFBLE1BQ1Y7QUFBQSxJQUNGO0FBQUEsRUFDRjtBQUFBLEVBRUEsUUFBUTtBQUFBLElBQ04sTUFBTTtBQUFBLElBQ04sT0FBTztBQUFBO0FBQUEsTUFFTCxjQUFjO0FBQUEsUUFDWixRQUFRO0FBQUE7QUFBQSxRQUNSLElBQUk7QUFBQSxRQUNKLGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxNQUNWO0FBQUE7QUFBQSxNQUVBLGFBQWE7QUFBQSxRQUNYLFFBQVE7QUFBQSxRQUNSLElBQUk7QUFBQSxRQUNKLGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxNQUNWO0FBQUE7QUFBQSxNQUVBLFVBQVU7QUFBQSxRQUNSLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxRQUNSLFdBQVcsQ0FBQyxVQUFlO0FBQ3pCLGdCQUFNLEdBQUcsWUFBWSxhQUFhO0FBQUEsUUFDcEM7QUFBQSxNQUNGO0FBQUEsTUFDQSxXQUFXO0FBQUEsUUFDVCxRQUFRO0FBQUEsUUFDUixjQUFjO0FBQUEsUUFDZCxRQUFRO0FBQUEsUUFDUixXQUFXLENBQUMsVUFBZTtBQUN6QixnQkFBTSxHQUFHLFlBQVksYUFBYTtBQUFBLFFBQ3BDO0FBQUEsTUFDRjtBQUFBO0FBQUEsTUFFQSxhQUFhO0FBQUEsUUFDWCxRQUFRO0FBQUEsUUFDUixjQUFjO0FBQUEsUUFDZCxRQUFRO0FBQUEsTUFDVjtBQUFBO0FBQUE7QUFBQSxNQUlBLDRCQUE0QjtBQUFBLFFBQzFCLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxRQUNSLFNBQVMsQ0FBQyxTQUFpQjtBQUN6QixjQUFJLGFBQWEsUUFBUTtBQUN2QixtQkFBTyxLQUFLLFFBQVEsNEJBQTRCLHNCQUFzQjtBQUFBLFVBQ3hFO0FBQ0EsaUJBQU87QUFBQSxRQUNUO0FBQUEsTUFDRjtBQUFBO0FBQUE7QUFBQSxNQUlBLCtCQUErQjtBQUFBLFFBQzdCLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxNQUNWO0FBQUE7QUFBQSxNQUVBLHlCQUF5QjtBQUFBLFFBQ3ZCLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxNQUNWO0FBQUE7QUFBQTtBQUFBLE1BSUEsY0FBYyxFQUFFLFFBQVEsWUFBWSxjQUFjLE1BQU0sUUFBUSxNQUFNO0FBQUEsTUFDdEUsWUFBWSxFQUFFLFFBQVEsWUFBWSxjQUFjLE1BQU0sUUFBUSxNQUFNO0FBQUEsTUFDcEUsWUFBWSxFQUFFLFFBQVEsWUFBWSxjQUFjLE1BQU0sUUFBUSxNQUFNO0FBQUE7QUFBQTtBQUFBLE1BSXBFLGNBQWM7QUFBQSxRQUNaLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxNQUNWO0FBQUEsTUFDQSxnQkFBZ0I7QUFBQSxRQUNkLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxNQUNWO0FBQUE7QUFBQSxNQUVBLHlCQUF5QjtBQUFBLFFBQ3ZCLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxRQUNSLFNBQVM7QUFBQTtBQUFBLFFBQ1QsY0FBYztBQUFBO0FBQUEsTUFDaEI7QUFBQTtBQUFBLE1BRUEsZUFBZTtBQUFBLFFBQ2IsUUFBUTtBQUFBLFFBQ1IsY0FBYztBQUFBLFFBQ2QsUUFBUTtBQUFBLFFBQ1IsU0FBUztBQUFBO0FBQUEsUUFDVCxjQUFjO0FBQUE7QUFBQSxNQUNoQjtBQUFBO0FBQUEsTUFHQSxtQkFBbUIsRUFBRSxRQUFRLFlBQVksY0FBYyxNQUFNLFFBQVEsTUFBTTtBQUFBLE1BQzNFLGFBQWEsRUFBRSxRQUFRLFlBQVksY0FBYyxNQUFNLFFBQVEsTUFBTTtBQUFBLE1BQ3JFLGtCQUFrQixFQUFFLFFBQVEsWUFBWSxjQUFjLE1BQU0sUUFBUSxNQUFNO0FBQUEsTUFDMUUsV0FBVyxFQUFFLFFBQVEsWUFBWSxjQUFjLE1BQU0sUUFBUSxNQUFNO0FBQUE7QUFBQSxJQUdyRTtBQUFBLEVBQ0Y7QUFBQSxFQUVBLE9BQU87QUFBQSxJQUNMLGVBQWU7QUFBQSxNQUNiLE9BQU87QUFBQSxRQUNMLE9BQU8sUUFBUSxXQUFXLFlBQVk7QUFBQSxRQUN0QyxtQkFBbUIsUUFBUSxXQUFXLHNCQUFzQjtBQUFBLFFBQzVELCtCQUErQixRQUFRLFdBQVcsNkJBQTZCO0FBQUEsUUFDL0UsZUFBZSxRQUFRLFdBQVcsa0JBQWtCO0FBQUEsUUFDcEQsbUJBQW1CLFFBQVEsV0FBVyxzQkFBc0I7QUFBQSxRQUM1RCxRQUFRLFFBQVEsV0FBVyxhQUFhO0FBQUEsUUFDeEMsVUFBVSxRQUFRLFdBQVcsZUFBZTtBQUFBLFFBQzVDLFdBQVcsUUFBUSxXQUFXLGdCQUFnQjtBQUFBLFFBQzlDLFVBQVUsUUFBUSxXQUFXLGVBQWU7QUFBQSxRQUM1QyxNQUFNLFFBQVEsV0FBVyxXQUFXO0FBQUEsUUFDcEMsV0FBVyxRQUFRLFdBQVcsZ0JBQWdCO0FBQUEsUUFDOUMsZUFBZSxRQUFRLFdBQVcsa0JBQWtCO0FBQUEsUUFDcEQsUUFBUSxRQUFRLFdBQVcsYUFBYTtBQUFBLFFBQ3hDLGFBQWEsUUFBUSxXQUFXLGtCQUFrQjtBQUFBLFFBQ2xELGFBQWEsUUFBUSxXQUFXLGtCQUFrQjtBQUFBLFFBQ2xELE9BQU8sUUFBUSxXQUFXLFlBQVk7QUFBQSxRQUN0QyxhQUFhLFFBQVEsV0FBVyxrQkFBa0I7QUFBQSxRQUNsRCxnQkFBZ0IsUUFBUSxXQUFXLHFCQUFxQjtBQUFBLFFBQ3hELG9CQUFvQixRQUFRLFdBQVcseUJBQXlCO0FBQUEsUUFDaEUsc0JBQXNCLFFBQVEsV0FBVywyQkFBMkI7QUFBQSxRQUNwRSwwQkFBMEIsUUFBUSxXQUFXLCtCQUErQjtBQUFBLFFBQzVFLDBCQUEwQixRQUFRLFdBQVcsd0JBQXdCO0FBQUEsUUFDckUsMEJBQTBCLFFBQVEsV0FBVyx3QkFBd0I7QUFBQSxRQUNyRSw4QkFBOEIsUUFBUSxXQUFXLDRCQUE0QjtBQUFBLFFBQzdFLHVCQUF1QixRQUFRLFdBQVcscUJBQXFCO0FBQUEsUUFDL0QsVUFBVSxRQUFRLFdBQVcsZUFBZTtBQUFBLFFBQzVDLDBCQUEwQixRQUFRLFdBQVcsd0JBQXdCO0FBQUEsUUFDckUsa0JBQWtCLFFBQVEsV0FBVyxnQkFBZ0I7QUFBQSxRQUNyRCxjQUFjLFFBQVEsV0FBVyxZQUFZO0FBQUEsUUFDN0Msc0JBQXNCLFFBQVEsV0FBVyxvQkFBb0I7QUFBQSxRQUM3RCxnQkFBZ0IsUUFBUSxXQUFXLGNBQWM7QUFBQSxRQUNqRCxtQkFBbUIsUUFBUSxXQUFXLGlCQUFpQjtBQUFBLFFBQ3ZELHdCQUF3QixRQUFRLFdBQVcsc0JBQXNCO0FBQUEsUUFDakUsd0JBQXdCLFFBQVEsV0FBVyxzQkFBc0I7QUFBQSxRQUNqRSx5QkFBeUIsUUFBUSxXQUFXLHVCQUF1QjtBQUFBLFFBQ25FLHNCQUFzQixRQUFRLFdBQVcsb0JBQW9CO0FBQUE7QUFBQSxRQUU3RCxpQkFBaUIsUUFBUSxXQUFXLDRCQUE0QjtBQUFBLFFBQ2hFLHdCQUF3QixRQUFRLFdBQVcsbUNBQW1DO0FBQUEsUUFDOUUsa0JBQWtCLFFBQVEsV0FBVyw2QkFBNkI7QUFBQSxRQUNsRSxpQkFBaUIsUUFBUSxXQUFXLDRCQUE0QjtBQUFBLFFBQ2hFLFdBQVcsUUFBUSxXQUFXLHNCQUFzQjtBQUFBLFFBQ3BELFdBQVcsUUFBUSxXQUFXLHNCQUFzQjtBQUFBLFFBQ3BELG9CQUFvQixRQUFRLFdBQVcsK0JBQStCO0FBQUEsUUFDdEUsY0FBYyxRQUFRLFdBQVcseUJBQXlCO0FBQUEsUUFDMUQsaUJBQWlCLFFBQVEsV0FBVyw0QkFBNEI7QUFBQSxRQUNoRSxXQUFXLFFBQVEsV0FBVyxzQkFBc0I7QUFBQSxRQUNwRCxZQUFZLFFBQVEsV0FBVyx1QkFBdUI7QUFBQSxRQUN0RCxtQkFBbUIsUUFBUSxXQUFXLDhCQUE4QjtBQUFBLFFBQ3BFLGtCQUFrQixRQUFRLFdBQVcsNkJBQTZCO0FBQUEsUUFDbEUsd0JBQXdCLFFBQVEsV0FBVyw4QkFBOEI7QUFBQSxRQUN6RSw0QkFBNEIsUUFBUSxXQUFXLHVDQUF1QztBQUFBLFFBQ3RGLHlCQUF5QixRQUFRLFdBQVcsb0NBQW9DO0FBQUEsUUFDaEYsb0JBQW9CLFFBQVEsV0FBVywrQkFBK0I7QUFBQSxRQUN0RSxzQkFBc0IsUUFBUSxXQUFXLGlDQUFpQztBQUFBLFFBQzFFLHFCQUFxQixRQUFRLFdBQVcsZ0NBQWdDO0FBQUEsUUFDeEUscUJBQXFCLFFBQVEsV0FBVyx3QkFBd0I7QUFBQSxRQUNoRSxvQkFBb0IsUUFBUSxXQUFXLGtCQUFrQjtBQUFBLFFBQ3pELG1CQUFtQixRQUFRLFdBQVcsaUJBQWlCO0FBQUE7QUFBQSxRQUd2RCxpQkFBaUIsUUFBUSxXQUFXLG9CQUFvQjtBQUFBLFFBQ3hELGdCQUFnQixRQUFRLFdBQVcsbUJBQW1CO0FBQUEsUUFDdEQsWUFBWSxRQUFRLFdBQVcsZUFBZTtBQUFBLFFBQzlDLGlCQUFpQixRQUFRLFdBQVcsb0JBQW9CO0FBQUEsUUFDeEQsY0FBYyxRQUFRLFdBQVcsaUJBQWlCO0FBQUEsUUFDbEQsZUFBZSxRQUFRLFdBQVcsMEJBQTBCO0FBQUEsUUFDNUQsaUJBQWlCLFFBQVEsV0FBVyw0QkFBNEI7QUFBQSxRQUNoRSxpQkFBaUIsUUFBUSxXQUFXLG9CQUFvQjtBQUFBLFFBQ3hELG1CQUFtQixRQUFRLFdBQVcsc0JBQXNCO0FBQUEsUUFDNUQsZ0JBQWdCLFFBQVEsV0FBVyxtQkFBbUI7QUFBQSxRQUN0RCxhQUFhLFFBQVEsV0FBVyxnQkFBZ0I7QUFBQSxRQUNoRCxxQkFBcUIsUUFBUSxXQUFXLG1CQUFtQjtBQUFBLFFBQzNELHdCQUF3QixRQUFRLFdBQVcsc0JBQXNCO0FBQUEsUUFDakUseUJBQXlCLFFBQVEsV0FBVyx1QkFBdUI7QUFBQSxRQUNuRSxtQkFBbUIsUUFBUSxXQUFXLGlCQUFpQjtBQUFBO0FBQUEsUUFHdkQsbUJBQW1CLFFBQVEsV0FBVyxzQkFBc0I7QUFBQSxRQUM1RCxtQkFBbUIsUUFBUSxXQUFXLHNCQUFzQjtBQUFBLFFBQzVELHVCQUF1QixRQUFRLFdBQVcsMEJBQTBCO0FBQUEsUUFDcEUsd0JBQXdCLFFBQVEsV0FBVywyQkFBMkI7QUFBQTtBQUFBLE1BR3hFO0FBQUEsSUFDRjtBQUFBLEVBQ0Y7QUFDRixDQUFDOyIsCiAgIm5hbWVzIjogW10KfQo=
