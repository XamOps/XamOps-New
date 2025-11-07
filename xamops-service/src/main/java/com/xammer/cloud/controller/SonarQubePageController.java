package com.xammer.cloud.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * This controller serves the HTML page for the SonarQube dashboard,
 * allowing Thymeleaf to render the sidebar and header.
 */
@Controller
public class SonarQubePageController {

    @GetMapping("/sonarqube.html")
    public String sonarqubePage() {
        // This tells Spring Boot to find and render the template 
        // named "sonarqube" from the /resources/templates/ folder.
        return "sonarqube"; 
    }
}