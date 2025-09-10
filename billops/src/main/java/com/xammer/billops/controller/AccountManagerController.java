package com.xammer.billops.controller;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.Customer;
import com.xammer.billops.dto.AccountCreationRequestDto;
import com.xammer.billops.dto.GcpAccountRequestDto;
import com.xammer.billops.dto.VerifyAccountRequest;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.service.AwsAccountService;
import com.xammer.billops.service.CustomerService;
import com.xammer.billops.service.GcpDataService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 1. Changed to @RestController
@RestController
// 2. Updated the base path for a consistent API structure
@RequestMapping("/api/accounts")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public class AccountManagerController {

    private final AwsAccountService awsAccountService;
    private final GcpDataService gcpDataService;
    private final CustomerService customerService;
    private final CloudAccountRepository cloudAccountRepository;

    public AccountManagerController(AwsAccountService awsAccountService, GcpDataService gcpDataService,
                                    CustomerService customerService, CloudAccountRepository cloudAccountRepository) {
        this.awsAccountService = awsAccountService;
        this.gcpDataService = gcpDataService;
        this.customerService = customerService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    // 3. This method now returns a list of accounts as JSON
    @GetMapping
    public ResponseEntity<List<CloudAccount>> getAccounts(Authentication authentication) {
        if (authentication == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            Customer customer = customerService.findByUsernameWithCloudAccounts(authentication.getName());
            return ResponseEntity.ok(customer.getCloudAccounts());
        } catch (UsernameNotFoundException e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    // 4. This endpoint adds a GCP account and returns a status message as JSON
    @PostMapping("/add-gcp")
    public ResponseEntity<Map<String, String>> addGcpAccount(@RequestBody GcpAccountRequestDto request,
                                                             Authentication authentication) {
        Map<String, String> response = new HashMap<>();
        try {
            Customer customer = customerService.findByUsername(authentication.getName());
            gcpDataService.createGcpAccount(request, customer);
            response.put("message", "GCP account added successfully!");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", "Failed to add GCP account: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // 5. This endpoint remains the same as it was already returning JSON
    @PostMapping("/generate-stack-url")
    public ResponseEntity<Map<String, String>> generateStackUrl(@RequestBody AccountCreationRequestDto request, Authentication authentication) {
        Customer customer = customerService.findByUsername(authentication.getName());
        String url = awsAccountService.generateCloudFormationUrl(request.getAccountName(), customer);

        Map<String, String> response = new HashMap<>();
        response.put("url", url);

        return ResponseEntity.ok(response);
    }

    // 6. This endpoint verifies an account and returns a status
    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verifyAccount(@RequestBody VerifyAccountRequest request) {
        Map<String, String> response = new HashMap<>();
        try {
            awsAccountService.verifyAccount(request);
            response.put("message", "Account verified successfully!");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", "Failed to verify account: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // 7. Changed to @DeleteMapping for proper REST semantics
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteAccount(@PathVariable("id") Long id) {
        Map<String, String> response = new HashMap<>();
        try {
            cloudAccountRepository.deleteById(id);
            response.put("message", "Account removed successfully!");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", "Failed to remove account: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // 8. REMOVED: All methods that returned String view names (like showAccountManager, showAddAccountPage)
    // have been removed. Your frontend-app now handles all page routing.
}