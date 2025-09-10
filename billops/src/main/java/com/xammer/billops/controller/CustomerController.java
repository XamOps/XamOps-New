package com.xammer.billops.controller;

import com.xammer.billops.domain.Customer;
import com.xammer.billops.dto.ProfileUpdateRequestDto;
import com.xammer.billops.service.CustomerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

// 1. Changed to @RestController
@RestController
// 2. Updated the base path for API consistency
@RequestMapping("/api/customer")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    // 3. This method now returns the Customer object as JSON
    @GetMapping("/profile")
    public ResponseEntity<Customer> getProfile(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            Customer customer = customerService.findByUsername(authentication.getName());
            return ResponseEntity.ok(customer);
        } catch (UsernameNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // 4. Changed to @PutMapping for updates and uses @RequestBody
    @PutMapping("/profile")
    public ResponseEntity<Map<String, String>> updateProfile(@RequestBody ProfileUpdateRequestDto request, Authentication authentication) {
        Map<String, String> response = new HashMap<>();
        if (authentication == null) {
            response.put("error", "User not authenticated");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        try {
            customerService.updateCustomerArn(authentication.getName(), request.getAwsRoleArn());
            response.put("message", "Profile updated successfully!");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", "Failed to update profile: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}