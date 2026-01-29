package com.xammer.cloud.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller to handle root and login page requests.
 * Redirects to the Vite dev server running on port 5173 during development.
 * In production, configure your reverse proxy to serve frontend from the same
 * domain.
 */
@Controller
public class RootController {

    @GetMapping({ "/", "/login", "/unified-dashboard", "/dashboard", "/finops", "/cost", "/waste", "/rightsizing",
            "/reservation", "/cloudsitter", "/cloudlist", "/account-manager", "/security", "/performance", "/alerts",
            "/tickets", "/ticket-detail", "/cloudshell", "/add-account", "/devops-in-the-box", "/cicd-pipelines",
            "/grafana-dashboard", "/cloudk8s", "/eks-details" })
    public String redirectToFrontend() {
        // Redirect to Vite dev server during development
        return "redirect:http://localhost:5173/";
    }
}
