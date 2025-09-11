package com.xammer.billops.controller;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.User;
import com.xammer.billops.dto.CreditRequestDto;
import com.xammer.billops.dto.DashboardDataDto;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.billops.service.CostService;
import com.xammer.billops.service.DashboardService;
import com.xammer.billops.service.ResourceService;
import com.xammer.billops.service.ExcelExportService;
import com.xammer.billops.service.CreditRequestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/billing")
public class BillopsController {
    private final DashboardService dashboardService;
    private final CostService costService;
    private final ResourceService resourceService;
    private final UserRepository userRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final ExcelExportService excelExportService;
    private final CreditRequestService creditRequestService; // Make it final

    // Updated constructor to include CreditRequestService
    public BillopsController(DashboardService dashboardService, CostService costService,
                             ResourceService resourceService, UserRepository userRepository,
                             CloudAccountRepository cloudAccountRepository,
                             ExcelExportService excelExportService,
                             CreditRequestService creditRequestService) { // Add this parameter
        this.dashboardService = dashboardService;
        this.costService = costService;
        this.resourceService = resourceService;
        this.userRepository = userRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.excelExportService = excelExportService;
        this.creditRequestService = creditRequestService; // Initialize it
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<CloudAccount>> getCloudAccounts(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return ResponseEntity.ok(cloudAccountRepository.findByClientId(user.getClient().getId()));
    }

    @GetMapping("/data/{accountId}")
    public ResponseEntity<DashboardDataDto> getBillingData(@PathVariable Long accountId,
                                                           @RequestParam(required = false) Integer year,
                                                           @RequestParam(required = false) Integer month,
                                                           Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        Optional<CloudAccount> accountOpt = cloudAccountRepository.findById(accountId);

        if (accountOpt.isEmpty() || !accountOpt.get().getClient().getId().equals(user.getClient().getId())) {
            return ResponseEntity.notFound().build();
        }

        DashboardDataDto data = dashboardService.getDashboardData(accountOpt.get(), year, month);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/breakdown/regions")
    public ResponseEntity<List<Map<String, Object>>> getRegionBreakdown(@RequestParam Long accountId,
                                                                        @RequestParam String serviceName,
                                                                        @RequestParam(required = false) Integer year,
                                                                        @RequestParam(required = false) Integer month,
                                                                        Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        CloudAccount account = cloudAccountRepository.findById(accountId)
                .filter(acc -> acc.getClient().getId().equals(user.getClient().getId()))
                .orElseThrow(() -> new RuntimeException("Account not found or access denied"));

        List<Map<String, Object>> data = costService.getCostForServiceInRegion(account, serviceName, year, month);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/breakdown/instances")
    public ResponseEntity<List<Map<String, Object>>> getInstanceBreakdown(@RequestParam Long accountId,
                                                                          @RequestParam String region,
                                                                          @RequestParam String serviceName,
                                                                          Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        CloudAccount account = cloudAccountRepository.findById(accountId)
                .filter(acc -> acc.getClient().getId().equals(user.getClient().getId()))
                .orElseThrow(() -> new RuntimeException("Account not found or access denied"));

        List<Map<String, Object>> data = resourceService.getResourcesInRegion(account, region, serviceName);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportBillingData(@RequestParam Long accountId,
                                                    @RequestParam(required = false) Integer year,
                                                    @RequestParam(required = false) Integer month,
                                                    Authentication authentication) throws IOException {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        CloudAccount account = cloudAccountRepository.findById(accountId)
                .filter(acc -> acc.getClient().getId().equals(user.getClient().getId()))
                .orElseThrow(() -> new RuntimeException("Account not found or access denied"));

        ByteArrayInputStream in = excelExportService.generateBillingReport(account, year, month, costService, resourceService);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "attachment; filename=billing-report-" + account.getAccountName() + ".xlsx");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(in.readAllBytes());
    }

    // === Credit Request Endpoints ===

    // POST /api/billing/credits - User submits a new request
    @PostMapping("/credits")
    public ResponseEntity<CreditRequestDto> createCreditRequest(@RequestBody CreditRequestDto creditRequestDto) {
        return ResponseEntity.ok(creditRequestService.createCreditRequest(creditRequestDto));
    }

    // GET /api/billing/credits/admin/all - Admin gets all requests
    @GetMapping("/credits/admin/all")
    public ResponseEntity<List<CreditRequestDto>> getAllCreditRequests() {
        return ResponseEntity.ok(creditRequestService.getAllCreditRequests());
    }

    // GET /api/billing/credits/user/{userId} - User gets their own requests
    @GetMapping("/credits/user/{userId}")
    public ResponseEntity<List<CreditRequestDto>> getCreditRequestsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(creditRequestService.getCreditRequestsByUserId(userId));
    }

    // PUT /api/billing/credits/{id}/status - Admin updates a request's status
    @PutMapping("/credits/{id}/status")
    public ResponseEntity<CreditRequestDto> updateRequestStatus(@PathVariable Long id, @RequestBody Map<String, String> statusUpdate) {
        String status = statusUpdate.get("status");
        return ResponseEntity.ok(creditRequestService.updateRequestStatus(id, status));
    }
}
