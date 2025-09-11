package com.xammer.billops.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@CrossOrigin(origins = "http://localhost:5173")
public class AuthController {

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Logout successful");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/profile")
    public ResponseEntity<Map<String, Object>> getUserProfile() {
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", 1);
        profile.put("username", "admin");
        profile.put("email", "admin@example.com");
        profile.put("roles", new String[]{"ADMIN", "USER"});
        return ResponseEntity.ok(profile);
    }
}