package com.xammer.billops.controller;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.User;
import com.xammer.billops.dto.AwsCreditDto;
import com.xammer.billops.dto.BillingDashboardDto;
import com.xammer.billops.dto.CreditRequestDto;
import com.xammer.billops.dto.DashboardCardDto;
import com.xammer.billops.dto.ServiceCostDetailDto;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.dto.TicketReplyDto;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.billops.service.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/billops")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5500", "https://uat.xamops.com"}, allowCredentials = "true")
public class BillopsController {
    private final BillingService billingService;
    private final CostService costService;
    private final ResourceService resourceService;
    private final UserRepository userRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final ExcelExportService excelExportService;
    private final CreditRequestService creditRequestService;
    private final TicketService ticketService;
    private final DashboardService dashboardService;


     public BillopsController(BillingService billingService,
                             CostService costService,
                             ResourceService resourceService,
                             UserRepository userRepository,
                             CloudAccountRepository cloudAccountRepository,
                             ExcelExportService excelExportService,
                             CreditRequestService creditRequestService,
                             TicketService ticketService,
                             DashboardService dashboardService) {
        this.billingService = billingService;
        this.costService = costService;
        this.resourceService = resourceService;
        this.userRepository = userRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.excelExportService = excelExportService;
        this.creditRequestService = creditRequestService;
        this.ticketService = ticketService;
        this.dashboardService = dashboardService;
    }
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Billops service is running successfully!");
    }

    @GetMapping("/dashboard/cards")
    public ResponseEntity<DashboardCardDto> getDashboardCards() {
        return ResponseEntity.ok(dashboardService.getDashboardCards());
    }

    @GetMapping("/aws-credits")
    public ResponseEntity<AwsCreditDto> getAwsCredits(@RequestParam String accountId) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountIdIn(Collections.singletonList(accountId))
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        return ResponseEntity.ok(costService.getAwsCredits(account));
    }

    @GetMapping("/billing")
    public ResponseEntity<BillingDashboardDto> getBillingData(
            @RequestParam List<String> accountIds,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            BillingDashboardDto data = billingService.getBillingData(accountIds, year, month);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    // Added for backward compatibility
    @GetMapping("/billing/{accountId}")
    public ResponseEntity<BillingDashboardDto> getBillingData(
            @PathVariable String accountId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            BillingDashboardDto data = billingService.getBillingData(Collections.singletonList(accountId), year, month);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/detailed-breakdown")
    public ResponseEntity<List<ServiceCostDetailDto>> getDetailedBillingReport(
            @RequestParam List<String> accountIds,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            List<ServiceCostDetailDto> data = billingService.getDetailedBillingReport(accountIds, year, month);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    // Added for backward compatibility
    @GetMapping("/detailed-breakdown/{accountId}")
    public ResponseEntity<List<ServiceCostDetailDto>> getDetailedBillingReport(
            @PathVariable String accountId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            List<ServiceCostDetailDto> data = billingService.getDetailedBillingReport(Collections.singletonList(accountId), year, month);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<CloudAccount>> getCloudAccounts(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return ResponseEntity.ok(cloudAccountRepository.findByClientId(user.getClient().getId()));
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

    @PostMapping("/credits")
    public ResponseEntity<CreditRequestDto> createCreditRequest(@RequestBody CreditRequestDto creditRequestDto) {
        return ResponseEntity.ok(creditRequestService.createCreditRequest(creditRequestDto));
    }

    @GetMapping("/credits/admin/all")
    public ResponseEntity<List<CreditRequestDto>> getAllCreditRequests() {
        return ResponseEntity.ok(creditRequestService.getAllCreditRequests());
    }

    @GetMapping("/credits/user/{userId}")
    public ResponseEntity<List<CreditRequestDto>> getCreditRequestsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(creditRequestService.getCreditRequestsByUserId(userId));
    }

    @PutMapping("/credits/{id}/status")
    public ResponseEntity<CreditRequestDto> updateRequestStatus(@PathVariable Long id, @RequestBody Map<String, String> statusUpdate) {
        String status = statusUpdate.get("status");
        return ResponseEntity.ok(creditRequestService.updateRequestStatus(id, status));
    }

     @PostMapping("/tickets")
    public ResponseEntity<TicketDto> createTicket(@RequestBody TicketDto ticketDto) {
        return ResponseEntity.ok(ticketService.createTicket(ticketDto));
    }

    @GetMapping("/tickets")
    public ResponseEntity<List<TicketDto>> getAllTickets() {
        return ResponseEntity.ok(ticketService.getAllTickets());
    }

    // --- START: NEW TICKET ENDPOINTS ---

    @GetMapping("/tickets/{id}")
    public ResponseEntity<TicketDto> getTicketById(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.getTicketById(id));
    }

    @PostMapping("/tickets/{id}/replies")
    public ResponseEntity<TicketDto> addTicketReply(@PathVariable Long id, @RequestBody TicketReplyDto replyDto) {
        // In a real app, you would get the authorId from the authenticated user principal
        // For now, we assume it's sent in the DTO.
        return ResponseEntity.ok(ticketService.addReplyToTicket(id, replyDto));
    }

    @PostMapping("/tickets/{id}/close")
    // @PreAuthorize("hasRole('ROLE_BILLOPS_ADMIN')") // You can uncomment this for security
    public ResponseEntity<TicketDto> closeTicket(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.closeTicket(id));
    }
}