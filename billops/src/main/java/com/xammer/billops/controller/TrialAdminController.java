package com.xammer.billops.controller;

import com.xammer.billops.domain.Client;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin management of self-signup trial clients: listing, extending, and
 * converting to paid. Restricted to BillOps/Super admins.
 *
 * Trial state lives on the shared `client` table; the xamops-service trial
 * enforcement reads a Redis cache keyed by "trial:status:{clientId}", which we
 * evict here so admin changes take effect immediately.
 */
@RestController
@RequestMapping("/api/billops/admin/trials")
public class TrialAdminController {

    private static final Logger logger = LoggerFactory.getLogger(TrialAdminController.class);
    private static final String TRIAL_CACHE_PREFIX = "trial:status:";

    private final ClientRepository clientRepository;
    private final RedisCacheService redisCacheService;

    public TrialAdminController(ClientRepository clientRepository, RedisCacheService redisCacheService) {
        this.clientRepository = clientRepository;
        this.redisCacheService = redisCacheService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> listTrials(
            // Converted tenants drop out of a plan_type='TRIAL' list the instant they are
            // converted, so an operator who just clicked "convert" gets no confirmation row,
            // no audit view of past conversions, and no way to put the tenant back on a trial.
            // Opt-in so the default response stays exactly what it was.
            @RequestParam(name = "includeConverted", defaultValue = "false") boolean includeConverted) {
        List<Client> trials = includeConverted
                ? clientRepository.findTrialOrFormerTrial("TRIAL")
                : clientRepository.findByPlanType("TRIAL");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Client c : trials) {
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("clientId", c.getId());
            row.put("companyName", c.getName());
            row.put("email", c.getEmail());
            row.put("planType", c.getPlanType());
            row.put("trialStatus", c.getTrialStatus());
            row.put("trialEndAt", c.getTrialEndAt() != null ? c.getTrialEndAt().toString() : null);
            row.put("daysRemaining", daysRemaining(c));
            result.add(row);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{clientId}/extend")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> extend(@PathVariable Long clientId, @RequestBody Map<String, String> body) {
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.notFound().build();
        }

        int days = resolveDays(body);
        if (days <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Provide 'months' or 'days' > 0"));
        }

        LocalDateTime base = client.getTrialEndAt() != null && client.getTrialEndAt().isAfter(LocalDateTime.now())
                ? client.getTrialEndAt()
                : LocalDateTime.now();
        client.setPlanType("TRIAL");
        client.setTrialStatus("ACTIVE");
        client.setTrialEndAt(base.plusDays(days));
        // Clear sent flags so reminders can fire again in the new window.
        client.setReminder5SentAt(null);
        client.setFinalDaySentAt(null);
        clientRepository.save(client);
        redisCacheService.evict(TRIAL_CACHE_PREFIX + clientId);

        logger.info("Trial extended for client {} by {} days -> {}", clientId, days, client.getTrialEndAt());
        return ResponseEntity.ok(Map.of(
                "clientId", clientId,
                "trialStatus", "ACTIVE",
                "trialEndAt", client.getTrialEndAt().toString(),
                "daysRemaining", daysRemaining(client)));
    }

    @PostMapping("/{clientId}/convert")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> convert(@PathVariable Long clientId) {
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.notFound().build();
        }
        client.setPlanType("PAID");
        client.setTrialStatus("CONVERTED");
        clientRepository.save(client);
        redisCacheService.evict(TRIAL_CACHE_PREFIX + clientId);
        logger.info("Client {} converted to PAID", clientId);
        return ResponseEntity.ok(Map.of("clientId", clientId, "planType", "PAID", "trialStatus", "CONVERTED"));
    }

    private int resolveDays(Map<String, String> body) {
        try {
            if (body.containsKey("months")) {
                return Integer.parseInt(body.get("months").trim()) * 30;
            }
            if (body.containsKey("days")) {
                return Integer.parseInt(body.get("days").trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    private long daysRemaining(Client c) {
        if (c.getTrialEndAt() == null) return 0;
        long days = Duration.between(LocalDateTime.now(), c.getTrialEndAt()).toDays();
        return Math.max(days, 0);
    }
}
