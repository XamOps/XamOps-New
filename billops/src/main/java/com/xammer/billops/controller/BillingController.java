package com.xammer.billops.controller;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.Customer;
import com.xammer.billops.dto.DashboardDataDto;
import com.xammer.billops.service.CostService;
import com.xammer.billops.service.CustomerService;
import com.xammer.billops.service.DashboardService;
import com.xammer.billops.service.ResourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 1. Changed from @Controller to @RestController
@RestController
// 2. Added a base path for all billing-related APIs
@RequestMapping("/api/billing")
public class BillingController {

    private final CustomerService customerService;
    private final DashboardService dashboardService;
    private final CostService costService;
    private final ResourceService resourceService;

    public BillingController(CustomerService customerService, DashboardService dashboardService, CostService costService, ResourceService resourceService) {
        this.customerService = customerService;
        this.dashboardService = dashboardService;
        this.costService = costService;
        this.resourceService = resourceService;
    }

    // 3. NEW: Endpoint to get a list of a customer's cloud accounts for the dropdown
    @GetMapping("/accounts")
    public ResponseEntity<List<CloudAccount>> getCloudAccounts(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            Customer customer = customerService.findByUsernameWithCloudAccounts(authentication.getName());
            return ResponseEntity.ok(customer.getCloudAccounts());
        } catch (UsernameNotFoundException e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    // 4. Endpoint path updated to be relative to the new base path
    @GetMapping("/data/{accountId}")
    public ResponseEntity<DashboardDataDto> getBillingData(@PathVariable Long accountId,
                                                           @RequestParam(required = false) Integer year,
                                                           @RequestParam(required = false) Integer month,
                                                           Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(401).build();
        }
        Customer customer = customerService.findByUsernameWithCloudAccounts(authentication.getName());
        Optional<CloudAccount> accountOpt = customer.getCloudAccounts().stream()
                .filter(acc -> acc.getId().equals(accountId)).findFirst();

        if (accountOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DashboardDataDto data = dashboardService.getDashboardData(accountOpt.get(), year, month);
        return ResponseEntity.ok(data);
    }

    // 5. Endpoint path updated
    @GetMapping("/breakdown/regions")
    public ResponseEntity<List<Map<String, Object>>> getRegionBreakdown(@RequestParam Long accountId,
                                                                        @RequestParam String serviceName,
                                                                        @RequestParam(required = false) Integer year,
                                                                        @RequestParam(required = false) Integer month,
                                                                        Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(401).build();
        }
        Customer customer = customerService.findByUsernameWithCloudAccounts(authentication.getName());
        CloudAccount account = customer.getCloudAccounts().stream()
                .filter(acc -> acc.getId().equals(accountId)).findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found"));

        List<Map<String, Object>> data = costService.getCostForServiceInRegion(account, serviceName, year, month);
        return ResponseEntity.ok(data);
    }

    // 6. Endpoint path updated
    @GetMapping("/breakdown/instances")
    public ResponseEntity<List<Map<String, Object>>> getInstanceBreakdown(@RequestParam Long accountId,
                                                                          @RequestParam String region,
                                                                          @RequestParam String serviceName,
                                                                          Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(401).build();
        }
        Customer customer = customerService.findByUsernameWithCloudAccounts(authentication.getName());
        CloudAccount account = customer.getCloudAccounts().stream()
                .filter(acc -> acc.getId().equals(accountId)).findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found"));

        List<Map<String, Object>> data = resourceService.getResourcesInRegion(account, region, serviceName);
        return ResponseEntity.ok(data);
    }

    // 7. REMOVED: The old @GetMapping("/billing") that returned a String "billing" is no longer needed.
    // The frontend-app now handles the page itself.
}