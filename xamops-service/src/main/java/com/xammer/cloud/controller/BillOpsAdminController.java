package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.MarketplaceAccount;
import com.xammer.cloud.domain.MarketplacePayerAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.marketplace.BillingShareRequest;
import com.xammer.cloud.dto.marketplace.ConnectMarketplaceRequest;
import com.xammer.cloud.dto.marketplace.MarketplaceAccountSummaryDto;
import com.xammer.cloud.dto.billops.BillOpsClientDetailsDto;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.MarketplaceAccountRepository;
import com.xammer.cloud.repository.MarketplacePayerAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.CostDataSnapshotService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.billops.BillOpsClientListService;
import com.xammer.cloud.service.marketplace.MarketplaceAccountDiscoveryService;
import com.xammer.cloud.service.marketplace.MarketplaceBillingExtractionService;
import com.xammer.cloud.service.marketplace.MarketplaceBillingShareService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.xammer.cloud.service.billops.ClientSavingsService;
import com.xammer.cloud.dto.ClientSavingsDashboardDto;
import com.xammer.cloud.domain.CachedData;
import java.time.LocalDate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/xamops/billops-admin")
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_BILLOPS_ADMIN_SUB') or hasAuthority('ROLE_SUPER_ADMIN')")
public class BillOpsAdminController {

    private static final Logger log = LoggerFactory.getLogger(BillOpsAdminController.class);
    private static final String CLIENTS_CACHE_KEY = BillOpsClientListService.CLIENTS_CACHE_KEY;
    private static final int CLIENTS_CACHE_TTL_MINUTES = BillOpsClientListService.CLIENTS_CACHE_TTL_MINUTES;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MarketplacePayerAccountRepository marketplacePayerAccountRepository;

    @Autowired
    private MarketplaceAccountRepository marketplaceAccountRepository;

    @Autowired
    private MarketplaceAccountDiscoveryService marketplaceAccountDiscoveryService;

    @Autowired
    private MarketplaceBillingExtractionService marketplaceBillingExtractionService;

    @Autowired
    private MarketplaceBillingShareService marketplaceBillingShareService;

    @Autowired
    private CostDataSnapshotService costDataSnapshotService;

    @Autowired
    private ClientSavingsService clientSavingsService;

    /** Builds the client list, Upwind columns included. The cache warmer uses the same one. */
    @Autowired
    private BillOpsClientListService clientListService;

    /** Evict the clients cache (call this after creating a client or user) */
    @DeleteMapping("/clients/cache")
    public ResponseEntity<String> evictClientsCache() {
        redisCacheService.evict(CLIENTS_CACHE_KEY);
        // Sub-admins are served from their own key, so evicting only the primary one would leave
        // them refreshing into the same cached copy.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isSubAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_BILLOPS_ADMIN_SUB".equals(a.getAuthority()));
        if (isSubAdmin) {
            userRepository.findByUsername(auth.getName())
                    .ifPresent(u -> redisCacheService.evict(CLIENTS_CACHE_KEY + ":sub:" + u.getId()));
        }
        return ResponseEntity.ok("Cache evicted");
    }

    /** Toggle the starred (primary) flag for a client — visible to all ROLE_BILLOPS_ADMIN users */
    @PostMapping("/clients/{clientId}/star")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> toggleClientStar(@PathVariable Long clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Client not found: " + clientId));
        client.setStarred(!client.isStarred());
        clientRepository.save(client);
        // Evict all admin caches so the star is reflected on next fetch
        redisCacheService.evict(CLIENTS_CACHE_KEY);
        log.info("Client {} starred={}", clientId, client.isStarred());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", clientId);
        response.put("starred", client.isStarred());
        return ResponseEntity.ok(response);
    }

    /**
     * Grant or withdraw a client's ability to connect their own accounts to XamSecure.
     *
     * Explicit value rather than a toggle, unlike {@code /star} above: this one gates a paid
     * capability, and a toggle makes a double-submitted request silently reverse the decision.
     * Sending the intended state means a retry is idempotent.
     *
     * Enabled for every client by default; this exists so it can be withdrawn per contract.
     * Onboarding consumes an Upwind license seat and places Xammer's shared Cloud account
     * credential inside the customer's AWS account, so it is worth being able to say no.
     *
     * Withdrawing access blocks NEW connections. It deliberately does not tear down existing
     * ones — silently disconnecting a customer's live security monitoring from an admin toggle
     * would stop their findings with no warning and no audit trail on their side.
     */
    @PostMapping("/clients/{clientId}/xamsecure-onboarding")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> setXamSecureOnboarding(
            @PathVariable Long clientId, @RequestBody Map<String, Object> body) {
        Object raw = body.get("enabled");
        if (!(raw instanceof Boolean)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Body must be {\"enabled\": true|false}"));
        }
        boolean enabled = (Boolean) raw;
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Client not found: " + clientId));
        client.setXamSecureOnboardingEnabled(enabled);
        clientRepository.save(client);
        redisCacheService.evict(CLIENTS_CACHE_KEY);
        log.info("Client {} xamSecureOnboardingEnabled={}", clientId, enabled);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", clientId);
        response.put("xamSecureOnboardingEnabled", enabled);
        return ResponseEntity.ok(response);
    }

    /**
     * Grant or withdraw a chargeable notification channel for a client.
     *
     * Explicit value rather than a toggle, for the same reason as {@code /xamsecure-onboarding}:
     * this gates spend, and a toggle makes a double-submitted request silently reverse the
     * decision. Sending the intended state means a retry is idempotent.
     *
     * Both channels default OFF. Every SMS and WhatsApp message is billed per send by the provider,
     * so a client only starts costing money once someone here says so. Email delivery is entirely
     * unaffected by these flags and remains the channel of record — withdrawing a channel narrows
     * delivery, it never silences a customer's alerts altogether.
     *
     * SMS and WhatsApp are separate grants, not one "mobile" switch: they are priced differently
     * and approved through different regimes (DLT registration vs Meta template review), so a
     * customer can legitimately have one and not the other.
     *
     * @param channel {@code sms} or {@code whatsapp}
     */
    @PostMapping("/clients/{clientId}/notification-channel/{channel}")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> setNotificationChannel(
            @PathVariable Long clientId,
            @PathVariable String channel,
            @RequestBody Map<String, Object> body) {
        Object raw = body.get("enabled");
        if (!(raw instanceof Boolean)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Body must be {\"enabled\": true|false}"));
        }
        boolean enabled = (Boolean) raw;

        String normalised = channel == null ? "" : channel.trim().toLowerCase();
        if (!"sms".equals(normalised) && !"whatsapp".equals(normalised)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown channel '" + channel + "' — expected 'sms' or 'whatsapp'"));
        }

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Client not found: " + clientId));

        if ("sms".equals(normalised)) {
            client.setSmsNotificationsEnabled(enabled);
        } else {
            client.setWhatsappNotificationsEnabled(enabled);
        }
        clientRepository.save(client);
        redisCacheService.evict(CLIENTS_CACHE_KEY);

        log.info("Client {} {}NotificationsEnabled={}", clientId, normalised, enabled);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", clientId);
        response.put("smsNotificationsEnabled", client.isSmsNotificationsEnabled());
        response.put("whatsappNotificationsEnabled", client.isWhatsappNotificationsEnabled());
        return ResponseEntity.ok(response);
    }

    /**
     * Delete a client that has no users and no cloud accounts.
     *
     * <p>
     * The gap this fills: a client with zero users is listed here (this endpoint's GET returns
     * every client) but is invisible on the user-driven Admin User Manager, which groups a user
     * list by client name — an empty client produces no group and so has no row to carry a delete
     * action. This gives the one screen the empty client actually appears on a way to remove it.
     *
     * <p>
     * Guarded, not a cascade. {@link Client} maps {@code users} and {@code cloudAccounts} with
     * {@code cascade=ALL, orphanRemoval=true}, and {@code CloudAccount} cascades the same way down
     * to invoices, so a bare {@code clientRepository.delete(client)} would silently take out every
     * user, account and invoice beneath it. This refuses with 409 whenever the client still has
     * either, naming the counts, so the only client that can be deleted is one that is genuinely
     * empty. Those two relations are the destructive-cascade roots: every other {@code client_id}
     * satellite table (LLM config, observability, DSPM mapping, SRE, …) is only ever populated
     * after a client has a user and/or a connected account, so a client with neither has none of
     * them.
     *
     * <p>
     * Restricted to {@code ROLE_BILLOPS_ADMIN} / {@code ROLE_SUPER_ADMIN}, matching the other write
     * endpoints here rather than the broader class-level annotation — sub-admins can view the
     * client list but not mutate it.
     */
    @DeleteMapping("/clients/{clientId}")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> deleteClient(@PathVariable Long clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Client not found: " + clientId));

        long userCount = userRepository.countByClientId(clientId);
        long accountCount = cloudAccountRepository.countByClientId(clientId);
        if (userCount > 0 || accountCount > 0) {
            log.info("Refused delete of client {} ({}): {} user(s), {} cloud account(s)",
                    clientId, client.getName(), userCount, accountCount);
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("clientId", clientId);
            conflict.put("userCount", userCount);
            conflict.put("accountCount", accountCount);
            conflict.put("message", "Cannot delete client '" + client.getName() + "': it still has "
                    + userCount + " user(s) and " + accountCount + " cloud account(s). Remove them first.");
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(conflict);
        }

        clientRepository.delete(client);

        // Both cache tiers, exactly as setUserEmailNotifications does: the client list is cached
        // under a shared key and additionally per sub-admin under ":sub:{id}", so evicting only the
        // shared key would leave a sub-admin's list still showing the deleted client.
        redisCacheService.evict(CLIENTS_CACHE_KEY);
        redisCacheService.evictByPattern(CLIENTS_CACHE_KEY + ":sub:*");

        // Deliberately at info: the audit trail for who removed a client and when.
        log.info("Client {} ({}) deleted by admin", clientId, client.getName());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", clientId);
        response.put("deleted", true);
        return ResponseEntity.ok(response);
    }

    /**
     * Switch security and WAR email on or off for a single user of a client.
     *
     * <p>
     * The gap this fills: every notification switch that existed before was client-scoped, so
     * quietening one person meant silencing the whole tenant. A ten-user tenant where two people
     * own security had eight people receiving WAR reports and finding digests they could not act
     * on, and the usual response to that is a mail rule — after which nobody on the tenant reads
     * them either.
     *
     * <p>
     * Scope is narrow and worth stating precisely, because "email notifications off" invites a
     * wider reading than this implements. Suppressed: the WAR onboarding report, security finding
     * notifications, and the AWS and GCP alert digests. Not suppressed: cost and operational mail
     * (monthly digest, budget alerts, DB health), login OTP, password reset, invitations, ticket
     * correspondence, and the in-app notification bell. Someone who is off the security rota may
     * still own the bill, still needs to sign in, and should still see findings in the product.
     *
     * <p>
     * Explicit value rather than a toggle, matching {@code /xamsecure-onboarding} and
     * {@code /notification-channel}: a double-submitted request or a retry must not silently
     * reverse the decision. Sending the intended state makes it idempotent.
     *
     * <p>
     * {@code userId} is validated against {@code clientId} rather than trusted on its own. A full
     * BillOps admin can see every client so this is not a privilege boundary, but it does mean a
     * stale page cannot write to a user it is no longer displaying — it gets a 404 instead.
     *
     * <p>
     * Restricted to {@code ROLE_BILLOPS_ADMIN} / {@code ROLE_SUPER_ADMIN}, consistent with the
     * other write endpoints here: sub-admins can view the client list but not change it.
     */
    @PostMapping("/clients/{clientId}/users/{userId}/email-notifications")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> setUserEmailNotifications(
            @PathVariable Long clientId,
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body) {
        Object raw = body.get("enabled");
        if (!(raw instanceof Boolean)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Body must be {\"enabled\": true|false}"));
        }
        boolean enabled = (Boolean) raw;

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "User not found: " + userId));

        Long ownerClientId = user.getClient() == null ? null : user.getClient().getId();
        if (!clientId.equals(ownerClientId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "User " + userId + " does not belong to client " + clientId);
        }

        user.setEmailNotificationsEnabled(enabled);
        userRepository.save(user);

        // Both cache tiers, not just the primary one. The client list is cached for five minutes
        // under a shared key and additionally per sub-admin under ":sub:{id}", so evicting only
        // the shared key would leave a sub-admin looking at the old state of a toggle whose entire
        // purpose is to be trusted at a glance.
        redisCacheService.evict(CLIENTS_CACHE_KEY);
        redisCacheService.evictByPattern(CLIENTS_CACHE_KEY + ":sub:*");

        // Deliberately at info: this is the audit trail for "why did this person stop receiving
        // security mail", and the answer needs to be findable months later.
        log.info("User {} ({}) on client {} emailNotificationsEnabled={}",
                userId, user.getUsername(), clientId, enabled);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", clientId);
        response.put("userId", userId);
        response.put("emailNotificationsEnabled", enabled);
        return ResponseEntity.ok(response);
    }

    /** Debug: check last_login_at values directly from DB (bypasses Redis cache) */
    @GetMapping("/debug/last-login")
    public ResponseEntity<List<java.util.Map<String, Object>>> debugLastLogin() {
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        userRepository.findAll().forEach(user -> {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("username", user.getUsername());
            row.put("lastLoginAt", user.getLastLoginAt() != null ? user.getLastLoginAt().toString() : null);
            result.add(row);
        });
        return ResponseEntity.ok(result);
    }

    @GetMapping("/clients")
    public List<BillOpsClientDetailsDto> getClients() {
        log.info("Fetching client details for BillOps Admin (Optimised)");

        // Detect ROLE_BILLOPS_ADMIN_SUB — scope to their owned clients only
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isSubAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_BILLOPS_ADMIN_SUB".equals(a.getAuthority()));

        if (isSubAdmin) {
            // Sub-admin: find their DB user record by username to get their ID
            String username = auth.getName();
            Optional<User> subAdminUser = userRepository.findByUsername(username);
            if (!subAdminUser.isPresent()) {
                log.warn("SUB_ADMIN '{}' not found in user table — returning empty client list", username);
                return new ArrayList<>();
            }
            Long subAdminId = subAdminUser.get().getId();
            // Use a per-user cache key so sub-admins never share the primary admin cache
            String subCacheKey = CLIENTS_CACHE_KEY + ":sub:" + subAdminId;
            Optional<List<BillOpsClientDetailsDto>> subCached = redisCacheService.get(
                    subCacheKey, new TypeReference<List<BillOpsClientDetailsDto>>() {});
            if (subCached.isPresent()) {
                log.info("Returning cached sub-admin client details for user {}", subAdminId);
                return subCached.get();
            }
            List<Client> clients = clientRepository.findByOwnerAdminId(subAdminId);
            log.info("SUB_ADMIN {} owned clients: {}", subAdminId, clients.size());
            if (clients.isEmpty()) return new ArrayList<>();
            List<BillOpsClientDetailsDto> result = clientListService.build(clients);
            redisCacheService.put(subCacheKey, result, CLIENTS_CACHE_TTL_MINUTES);
            return result;
        }

        // 0. Return cached response if available (primary admin only)
        Optional<List<BillOpsClientDetailsDto>> cached = redisCacheService.get(
                CLIENTS_CACHE_KEY, new TypeReference<List<BillOpsClientDetailsDto>>() {});
        if (cached.isPresent()) {
            log.info("Returning client details from Redis cache");
            return cached.get();
        }

        // 1. Fetch all clients and build response
        List<Client> clients = clientRepository.findAll();
        if (clients.isEmpty()) return new ArrayList<>();
        List<BillOpsClientDetailsDto> result = clientListService.build(clients);
        redisCacheService.put(CLIENTS_CACHE_KEY, result, CLIENTS_CACHE_TTL_MINUTES);
        return result;
    }

    // =========================================================
    // MARKETPLACE BILLING ENDPOINTS
    // =========================================================

    /**
     * Connect a marketplace payer account and auto-discover all member accounts.
     * Call this once with the IAM Role ARN created in the payer account.
     *
     * POST /api/xamops/billops-admin/marketplace/connect
     * Body: { "accountName": "My Marketplace", "roleArn": "arn:aws:iam::123456789012:role/XamOpsMarketRole" }
     */
    @PostMapping("/marketplace/connect")
    public ResponseEntity<Map<String, Object>> connectMarketplace(
            @RequestBody ConnectMarketplaceRequest request) {
        log.info("Connecting marketplace account with roleArn: {}", request.getRoleArn());
        MarketplacePayerAccount payer = marketplaceAccountDiscoveryService.connectAndDiscover(request);
        List<MarketplaceAccount> discovered = marketplaceAccountRepository.findByPayerAccount(payer);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("payerAccountId", payer.getId());
        response.put("payerAwsAccountId", payer.getPayerAwsAccountId());
        response.put("accountName", payer.getAccountName());
        response.put("totalDiscovered", discovered.size());
        response.put("marketplaceXamops",
                discovered.stream().filter(a -> "MARKETPLACE_XAMOPS".equals(a.getCategory())).count());
        response.put("marketplaceOnly",
                discovered.stream().filter(a -> "MARKETPLACE_ONLY".equals(a.getCategory())).count());
        response.put("status", "CONNECTED");
        return ResponseEntity.ok(response);
    }

    /**
     * Return the connected payer account(s) so the UI can retrieve payerAccountId on page load.
     *
     * GET /api/xamops/billops-admin/marketplace/payers
     */
    @GetMapping("/marketplace/payers")
    public ResponseEntity<List<Map<String, Object>>> getMarketplacePayers() {
        List<MarketplacePayerAccount> payers = marketplacePayerAccountRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (MarketplacePayerAccount payer : payers) {
            List<MarketplaceAccount> discovered = marketplaceAccountRepository.findByPayerAccount(payer);
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("payerAccountId", payer.getId());
            dto.put("payerAwsAccountId", payer.getPayerAwsAccountId());
            dto.put("accountName", payer.getAccountName());
            dto.put("totalDiscovered", discovered.size());
            dto.put("marketplaceXamops",
                    discovered.stream().filter(a -> "MARKETPLACE_XAMOPS".equals(a.getCategory())).count());
            dto.put("marketplaceOnly",
                    discovered.stream().filter(a -> "MARKETPLACE_ONLY".equals(a.getCategory())).count());
            dto.put("status", payer.getStatus());
            dto.put("markupPercent", payer.getMarkupPercent());
            result.add(dto);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * List all discovered marketplace accounts with their extraction status.
     *
     * GET /api/xamops/billops-admin/marketplace/accounts
     */
    @GetMapping("/marketplace/accounts")
    public ResponseEntity<List<MarketplaceAccountSummaryDto>> getMarketplaceAccounts() {
        List<MarketplacePayerAccount> payers = marketplacePayerAccountRepository.findAll();
        List<MarketplaceAccountSummaryDto> result = new ArrayList<>();
        for (MarketplacePayerAccount payer : payers) {
            marketplaceAccountRepository.findByPayerAccount(payer).forEach(account -> {
                MarketplaceAccountSummaryDto dto = new MarketplaceAccountSummaryDto();
                dto.setId(account.getId());
                dto.setAwsAccountId(account.getAwsAccountId());
                dto.setAccountName(account.getAccountName());
                dto.setContactEmail(account.getContactEmail());
                dto.setCategory(account.getCategory());
                dto.setBillingExtracted(account.getBillingExtracted());
                dto.setStatus(account.getStatus());
                dto.setDiscoveredAt(account.getDiscoveredAt());
                result.add(dto);
            });
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Returns the list of months (e.g. ["2025-02","2025-03"]) for which billing data
     * has been extracted into DB for the given account.
     *
     * GET /api/xamops/billops-admin/marketplace/accounts/{accountId}/billing-months
     */
    @GetMapping("/marketplace/accounts/{accountId}/billing-months")
    public ResponseEntity<List<String>> getBillingMonths(@PathVariable String accountId) {
        List<String> months = marketplaceBillingExtractionService.getAvailableMonths(accountId);
        return ResponseEntity.ok(months);
    }

    /**
     * Extract billing data for ALL marketplace accounts (Dec 2024 – Mar 2026).
     * Run this ONCE before applying Billing Conductor.
     * Saves CSV to S3 and summary rows to DB for every account.
     *
     * POST /api/xamops/billops-admin/marketplace/extract-all/{payerAccountId}
     */
    @PostMapping("/marketplace/extract-all/{payerAccountId}")
    public ResponseEntity<Map<String, String>> extractAllBilling(
            @PathVariable Long payerAccountId) {
        log.info("Starting bulk billing extraction for payer account {}", payerAccountId);
        Map<String, String> results = marketplaceBillingExtractionService.extractAll(payerAccountId);
        return ResponseEntity.ok(results);
    }

    /**
     * Share billing data for a single marketplace account via email.
     * Generates a 48-hour S3 download link and emails it to the customer.
     *
     * POST /api/xamops/billops-admin/marketplace/share-billing
     * Body: { "awsAccountId": "123456789012", "recipientEmail": "customer@example.com" }
     */
    @PostMapping("/marketplace/share-billing")
    public ResponseEntity<Map<String, String>> shareBilling(
            @RequestBody BillingShareRequest request) {
        log.info("Sharing billing data for account {} → {}",
                request.getAwsAccountId(), request.getRecipientEmail());
        marketplaceBillingShareService.shareBillingData(
                request.getAwsAccountId(), request.getRecipientEmail(), request.getYearMonth());
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "EMAIL_SENT");
        response.put("awsAccountId", request.getAwsAccountId());
        response.put("yearMonth", request.getYearMonth());
        response.put("sentTo", request.getRecipientEmail());
        response.put("message", "Billing data download link sent successfully (valid 48 hours)");
        return ResponseEntity.ok(response);
    }

    /**
     * Set (or update) the markup percentage for a payer account.
     * All billing rows will be multiplied by (1 + markupPercent/100) during the next extraction.
     * Re-run extract-all after updating to regenerate CSVs with the new markup applied.
     *
     * PUT /api/xamops/billops-admin/marketplace/payers/{id}/markup
     * Body: { "markupPercent": 5.3 }
     */
    @PutMapping("/marketplace/payers/{id}/markup")
    public ResponseEntity<Map<String, Object>> updateMarkup(
            @PathVariable Long id,
            @RequestBody Map<String, Double> body) {
        double markupPercent = body.getOrDefault("markupPercent", 0.0);
        MarketplacePayerAccount payer = marketplacePayerAccountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payer account not found: " + id));
        payer.setMarkupPercent(markupPercent);
        marketplacePayerAccountRepository.save(payer);
        log.info("Updated markup for payer {} ({}) to {}%",
                payer.getId(), payer.getAccountName(), markupPercent);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("payerAccountId", payer.getId());
        response.put("accountName", payer.getAccountName());
        response.put("markupPercent", payer.getMarkupPercent());
        response.put("message", "Markup updated. Re-run extract-all to apply to billing CSVs.");
        return ResponseEntity.ok(response);
    }

    // =========================================================
    // COST HISTORY SNAPSHOT ENDPOINTS
    // =========================================================

    /**
     * Pre-extract ALL historical cost months (Jan 2025 → last month) for every
     * active AWS account and store them in cached_data with absolute month keys.
     *
     * Run this ONCE before Billing Conductor is applied (before April 1st).
     * Already-snapshotted months are skipped automatically.
     *
     * POST /api/xamops/billops-admin/snapshot-cost-history
     */
    @PostMapping("/snapshot-cost-history")
    public ResponseEntity<Map<String, Object>> snapshotCostHistory() {
        log.info("Starting historical cost snapshot (pre-Billing-Conductor extraction)...");
        Map<String, String> accountResults = costDataSnapshotService.snapshotAllHistoricalMonths();

        long ok   = accountResults.values().stream().filter(v -> v.startsWith("OK")).count();
        long fail = accountResults.size() - ok;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", fail == 0 ? "COMPLETE" : "PARTIAL");
        response.put("accountsProcessed", accountResults.size());
        response.put("accountsOk", ok);
        response.put("accountsFailed", fail);
        response.put("details", accountResults);
        response.put("message", "Historical cost snapshots saved to cached_data. Closed months will now " +
                "be served from DB snapshots instead of the CE API.");
        return ResponseEntity.ok(response);
    }

    /**
     * Snapshot current month's final cost data for all AWS accounts.
     * Normally called automatically by the end-of-month scheduler but can be
     * triggered manually if needed.
     *
     * POST /api/xamops/billops-admin/snapshot-current-month
     */
    @PostMapping("/snapshot-current-month")
    public ResponseEntity<Map<String, String>> snapshotCurrentMonth() {
        log.info("Manual trigger: snapshotting current month final cost data...");
        costDataSnapshotService.snapshotCurrentMonthFinal();
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "OK");
        response.put("message", "Current month cost data snapshotted for all AWS accounts.");
        return ResponseEntity.ok(response);
    }

    /**
     * Returns a per-account, per-month snapshot coverage report.
     * Shows which months are fully saved, partially saved, or missing.
     *
     * Response per account:
     *   status        → COMPLETE | INCOMPLETE | PARTIAL_KEYS | NOT_STARTED
     *   complete      → list of YYYY-MM months where all 7 key types are present
     *   missing       → list of YYYY-MM months where no key types are present
     *   partial       → map of YYYY-MM → [missing key type names] (some keys saved, some not)
     *
     * GET /api/xamops/billops-admin/snapshot-status
     */
    @GetMapping("/snapshot-status")
    public ResponseEntity<Map<String, Object>> getSnapshotStatus() {
        log.info("Fetching snapshot coverage status...");
        Map<String, Object> report = costDataSnapshotService.getSnapshotStatusReport();
        return ResponseEntity.ok(report);
    }

    // =========================================================
    // CLIENT SAVINGS ENDPOINT
    // =========================================================

    @GetMapping("/clients/{clientId}/savings")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ClientSavingsDashboardDto> getClientSavings(
            @PathVariable Long clientId,
            @RequestParam(required = false) String preset,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) Long accountId) {

        LocalDate[] range = resolveSavingsRange(preset, startDate, endDate);
        try {
            return ResponseEntity.ok(clientSavingsService.getSavings(clientId, accountId, range[0], range[1]));
        } catch (java.util.NoSuchElementException e) {
            // The account belongs to another client (or doesn't exist): answer as if it doesn't
            // exist, so this endpoint can't be used to read another client's savings.
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Account not found for this client");
        }
    }

    private LocalDate[] resolveSavingsRange(String preset, String startDate, String endDate) {
        LocalDate end = LocalDate.now();
        if (preset != null) {
            return switch (preset) {
                case "7d"  -> new LocalDate[]{ end.minusDays(7),  end };
                case "90d" -> new LocalDate[]{ end.minusDays(90), end };
                default    -> new LocalDate[]{ end.minusDays(30), end };
            };
        }
        if (startDate != null && endDate != null) {
            try {
                LocalDate start = LocalDate.parse(startDate);
                LocalDate end2 = LocalDate.parse(endDate);
                if (start.isAfter(end2)) {
                    throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "startDate must be on or before endDate");
                }
                return new LocalDate[]{ start, end2 };
            } catch (java.time.format.DateTimeParseException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid date format, expected YYYY-MM-DD");
            }
        }
        return new LocalDate[]{ end.minusDays(30), end };
    }
}
