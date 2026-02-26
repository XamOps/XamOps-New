package com.xammer.cloud.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller to handle root and frontend route requests.
 * Redirects all known frontend routes to the configured frontend URL so the
 * React SPA (served by Vite locally or nginx in production) can handle routing.
 *
 * Explicit list is intentional:
 * - Avoids redirect loop at localhost (/**  would redirect to :8080/ → loop)
 * - Avoids intercepting SockJS HTTP polling for /xamops-ws/** (Spring MVC order=0
 *   beats WebSocketHandlerMapping order=MAX_VALUE-2, so /** would break WebSocket)
 */
@Controller
public class RootController {

    @Value("${app.frontend.url:http://localhost:5173}")
    private String frontendUrl;

    @GetMapping({
        "/", "/unified-dashboard", "/dashboard", "/gcp-dashboard",
        "/azure-dashboard", "/azure-finops", "/finops", "/gcp-finops",
        "/cost", "/gcp-cost", "/azure-cost",
        "/waste", "/gcp-waste", "/azure-waste",
        "/rightsizing", "/gcp-rightsizing", "/azure-rightsizing",
        "/reservation", "/gcp-reservations", "/azure-reservations",
        "/cloudsitter", "/spot-automation", "/autospotting",
        "/asg-detail", "/asg-config", "/flex-compute",
        "/grafana-dashboard", "/cloudk8s", "/gcp-cloudk8s", "/azure-cloudk8s",
        "/eks-details", "/gke-details", "/aks-details",
        "/security", "/gcp-security", "/azure-security",
        "/performance", "/gcp-performance", "/azure-performance",
        "/alerts", "/gcp-alerts", "/azure-alerts",
        "/tickets", "/ticket-detail",
        "/cloudlist", "/gcp-cloudlist", "/azure-cloudlist",
        "/cloudshell", "/account-manager", "/cloudmap", "/gcp-cloudmap",
        "/add-account", "/add-gcp-account", "/add-github-config",
        "/devops-in-the-box", "/cicd-pipelines", "/sonarqube",
        "/aiops", "/dataops",
        "/billops-dashboard", "/billops-billing", "/billops-invoices",
        "/billops-gcp-billing", "/billops-client-details", "/billops-credits",
        "/billops-tickets", "/billops-ticket-detail",
        "/billops-support-dashboard", "/billops-admin-dashboard",
        "/autospotting-admin", "/billops-admin-invoices",
        "/billops-admin-credits", "/billops-admin-tickets",
        "/billops-agreements", "/billops-admin-agreements",
        "/marketplace-purchases", "/workspace-licenses",
        "/user-management", "/my-profile", "/complianceops",
        "/billops-admin-user-manager", "/gcp-autospotting", "/calculator",
        "/login"
    })
    public String redirectToFrontend() {
        return "redirect:" + frontendUrl + "/";
    }
}
