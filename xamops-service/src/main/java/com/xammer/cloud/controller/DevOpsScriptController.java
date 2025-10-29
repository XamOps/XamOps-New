package com.xammer.cloud.controller;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.repository.DevOpsScriptRepository; // <-- CHANGE BACK TO THIS
// import com.xammer.cloud.service.GitScriptService; // <-- Remove Git service
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
    private DevOpsScriptRepository devOpsScriptRepository; // <-- Inject the repository again

    @GetMapping
    public List<DevOpsScript> getAllScripts() {
        // Fetch from the database via the repository
        return devOpsScriptRepository.findAll();
    }
}