package com.xammer.cloud.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Human labels for the frontend routes worth reporting on.
 *
 * <p>Deliberately a curated subset, not a mirror of every {@code <Route>} in
 * {@code App.tsx}. Two reasons. Reporting "never visited" across all ~150 routes would bury
 * the signal under entries like {@code /add-account} and {@code /loader-preview}, which
 * nobody is expected to visit and whose absence means nothing. And a page someone cannot
 * reach — a GCP screen on an AWS-only tenant — is not an adoption gap, so padding the list
 * with everything would manufacture findings.
 *
 * <p>Page-time aggregates still cover every route the browser reports;
 * {@link #labelFor(String)} prettifies anything not listed here. This catalogue only governs
 * which routes can appear as <em>unvisited</em>.
 *
 * <p>Kept in sync by hand with {@code App.tsx}. A route renamed there and not here shows up
 * as a prettified path rather than a friendly label — degraded, not broken.
 */
@Component
public class RouteCatalog {

    private static final Map<String, String> TRACKED = new LinkedHashMap<>();

    static {
        // Cost & FinOps
        TRACKED.put("/unified-dashboard", "Unified Command Center");
        TRACKED.put("/finops", "FinOps");
        TRACKED.put("/cost", "Cost Explorer");
        TRACKED.put("/waste", "Waste");
        TRACKED.put("/rightsizing", "Rightsizing");
        TRACKED.put("/reservation", "Reservations");
        TRACKED.put("/ebs-rightsizing", "EBS Rightsizing");

        // Automation
        TRACKED.put("/cloudsitter", "CloudSitter");
        TRACKED.put("/spot-automation", "Spot Automation");
        TRACKED.put("/flex-compute", "Flex Compute");

        // Security & compliance
        TRACKED.put("/security", "Security");
        TRACKED.put("/xamsecure/:tab", "XamSecure");
        TRACKED.put("/xamlens/:tab", "XamLens");
        TRACKED.put("/war", "Well-Architected Review");

        // Operations
        TRACKED.put("/performance", "Performance");
        TRACKED.put("/alerts", "Alerts");
        TRACKED.put("/cloudlist", "Cloud Inventory");
        TRACKED.put("/cloudmap", "CloudMap");
        TRACKED.put("/cloudk8s", "Kubernetes");
        TRACKED.put("/cloudshell", "CloudShell");
        TRACKED.put("/resource-labels", "Resource Labels");
        TRACKED.put("/account-manager", "Account Manager");

        // DevOps & SRE
        TRACKED.put("/devops-in-the-box", "DevOps in a Box");
        TRACKED.put("/cicd-pipelines", "CI/CD Pipelines");
        TRACKED.put("/sonarqube", "SonarQube");
        TRACKED.put("/aiops", "AIOps");
        TRACKED.put("/dataops", "DataOps");
        TRACKED.put("/sre", "SRE");
        TRACKED.put("/observability", "Observability");

        // Support & billing
        TRACKED.put("/tickets", "Support Tickets");
        TRACKED.put("/billops-dashboard", "BillOps Dashboard");
        TRACKED.put("/billops-billing", "Billing");
        TRACKED.put("/billops-invoices", "Invoices");
        TRACKED.put("/billops-credits", "Credits");
        TRACKED.put("/media-ops", "MediaOps");
    }

    /** The routes that can be reported as never visited, in display order. */
    public Map<String, String> trackedRoutes() {
        return TRACKED;
    }

    /**
     * Label for a route, falling back to a readable form of the path itself.
     *
     * The fallback matters because page-time covers every route the browser sends, including
     * ones added to the app after this class was last touched. Showing {@code Gcp Finops} is
     * worse than a curated label but far better than a blank cell.
     */
    public String labelFor(String route) {
        if (route == null || route.isBlank()) {
            return "Unknown";
        }
        String known = TRACKED.get(route);
        if (known != null) {
            return known;
        }
        String cleaned = route.startsWith("/") ? route.substring(1) : route;
        // Drop a trailing path parameter: /sre/investigations/:id -> sre/investigations
        int paramAt = cleaned.indexOf("/:");
        if (paramAt > 0) {
            cleaned = cleaned.substring(0, paramAt);
        }
        cleaned = cleaned.replace('-', ' ').replace('/', ' ').trim();
        if (cleaned.isEmpty()) {
            return "Home";
        }
        String[] words = cleaned.split("\\s+");
        StringBuilder sb = new StringBuilder(cleaned.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                sb.append(word.substring(1));
            }
        }
        return sb.toString();
    }
}
