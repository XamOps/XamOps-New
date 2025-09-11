package com.xammer.billops.controller;

import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.User;
import com.xammer.billops.dto.AccountCreationRequestDto;
import com.xammer.billops.dto.GcpAccountRequestDto;
import com.xammer.billops.dto.VerifyAccountRequest;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.billops.service.AwsAccountService;
import com.xammer.billops.service.GcpDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
public class AccountManagerController {

    private final AwsAccountService awsAccountService;
    private final GcpDataService gcpDataService;
    private final UserRepository userRepository;
    private final CloudAccountRepository cloudAccountRepository;

    public AccountManagerController(AwsAccountService awsAccountService, GcpDataService gcpDataService,
                                    UserRepository userRepository, CloudAccountRepository cloudAccountRepository) {
        this.awsAccountService = awsAccountService;
        this.gcpDataService = gcpDataService;
        this.userRepository = userRepository;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @GetMapping
    public ResponseEntity<List<CloudAccount>> getAccounts(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return ResponseEntity.ok(cloudAccountRepository.findByClientId(user.getClient().getId()));
    }

    @PostMapping("/add-gcp")
    public ResponseEntity<Map<String, String>> addGcpAccount(@RequestBody GcpAccountRequestDto request,
                                                             Authentication authentication) {
        Map<String, String> response = new HashMap<>();
        try {
            User user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));
            Client client = user.getClient();
            gcpDataService.createGcpAccount(request, client);
            response.put("message", "GCP account added successfully!");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", "Failed to add GCP account: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/generate-stack-url")
    public ResponseEntity<Map<String, String>> generateStackUrl(@RequestBody AccountCreationRequestDto request, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        Client client = user.getClient();
        String url = awsAccountService.generateCloudFormationUrl(request.getAccountName(), client);

        Map<String, String> response = new HashMap<>();
        response.put("url", url);

        return ResponseEntity.ok(response);
    }

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
}