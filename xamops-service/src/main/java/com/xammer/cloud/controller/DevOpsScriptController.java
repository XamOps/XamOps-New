package com.xammer.cloud.controller;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.service.DevOpsScriptService; // Import the new service
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/devops-scripts")
@CrossOrigin(origins = "*")
public class DevOpsScriptController {

    @Autowired
    private DevOpsScriptService devOpsScriptService; // Inject Service instead of Repository

    @GetMapping
    public List<DevOpsScript> getAllScripts() {
        // Call the service method which handles the caching
        return devOpsScriptService.getAllScripts();
    }
}