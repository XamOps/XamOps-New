package com.xammer.cloud.permissions;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tells the UI why data is missing for an account.
 *
 * Read-only and cheap by design: the frontend calls this alongside every page
 * load, so it must never touch AWS — it only reports what the SDK interceptor
 * has already observed.
 */
@RestController
@RequestMapping("/api/xamops/account-health")
public class AccountHealthController {

    private static final Logger logger = LoggerFactory.getLogger(AccountHealthController.class);

    private final PermissionGapService permissionGapService;
    private final PermissionGapRegistry registry;
    private final CloudAccountRepository cloudAccountRepository;
    private final UserRepository userRepository;
    private final UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    private final AwsClientProvider awsClientProvider;

    public AccountHealthController(PermissionGapService permissionGapService,
            PermissionGapRegistry registry,
            CloudAccountRepository cloudAccountRepository,
            UserRepository userRepository,
            UserCloudAccountAccessRepository userCloudAccountAccessRepository,
            AwsClientProvider awsClientProvider) {
        this.permissionGapService = permissionGapService;
        this.registry = registry;
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.userCloudAccountAccessRepository = userCloudAccountAccessRepository;
        this.awsClientProvider = awsClientProvider;
    }

    /**
     * Permission gaps for the given accounts.
     *
     * @param accountIds comma-separated provider account ids; defaults to every
     *                   account the caller can see.
     * @param feature    optional {@link XamOpsFeature} name, so a page reports only
     *                   the gaps that blank out what the user is looking at.
     */
    @GetMapping("/permissions")
    public ResponseEntity<?> getPermissionStatus(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String accountIds,
            @RequestParam(required = false) String feature) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        if (!registry.isEnabled()) {
            return ResponseEntity.ok(emptyResponse());
        }

        XamOpsFeature requestedFeature = parseFeature(feature);
        List<CloudAccount> accessible = accessibleAccounts(userDetails);

        if (accountIds != null && !accountIds.isBlank()) {
            Set<String> requested = Arrays.stream(accountIds.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .collect(Collectors.toSet());
            accessible = accessible.stream()
                    .filter(account -> requested.contains(account.getProviderAccountId()))
                    .collect(Collectors.toList());
        }

        List<AccountPermissionStatusDto> statuses = accessible.stream()
                // Only AWS is instrumented today; returning GCP/Azure here would
                // imply a health check that is not actually running for them.
                .filter(account -> "AWS".equalsIgnoreCase(account.getProvider()))
                .filter(account -> account.getProviderAccountId() != null)
                .map(account -> permissionGapService.statusFor(account, requestedFeature))
                .collect(Collectors.toList());

        List<AccountPermissionStatusDto> impacted = statuses.stream()
                .filter(status -> status.getHealth() != AccountPermissionStatusDto.Health.HEALTHY)
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accounts", statuses);
        body.put("hasGaps", !impacted.isEmpty());
        body.put("impactedAccountCount", impacted.size());
        body.put("feature", requestedFeature != null ? requestedFeature.name() : null);
        return ResponseEntity.ok(body);
    }

    /**
     * Forgets what we know about an account's permissions and drops its cached AWS
     * clients, so the next data refresh re-tests the role from scratch. This is the
     * "I fixed my policy, check again" button.
     */
    @PostMapping("/permissions/recheck")
    public ResponseEntity<?> recheck(@AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam String accountId) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        boolean allowed = accessibleAccounts(userDetails).stream()
                .anyMatch(account -> accountId.equals(account.getProviderAccountId()));
        if (!allowed) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You do not have access to this account"));
        }

        registry.clear(accountId);
        awsClientProvider.evictAccount(accountId);
        logger.info("Permission gaps cleared and clients evicted for account {} on user request", accountId);

        return ResponseEntity.ok(Map.of(
                "message", "Access re-check started. Refresh the page in a minute to see updated results.",
                "accountId", accountId));
    }

    private Map<String, Object> emptyResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accounts", List.of());
        body.put("hasGaps", false);
        body.put("impactedAccountCount", 0);
        body.put("feature", null);
        return body;
    }

    private XamOpsFeature parseFeature(String feature) {
        if (feature == null || feature.isBlank()) {
            return null;
        }
        try {
            return XamOpsFeature.valueOf(feature.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.debug("Unknown feature '{}' requested for permission status", feature);
            return null;
        }
    }

    /**
     * Accounts this caller may see. Mirrors the rules in AccountManagerController —
     * a permission gap names the account and its role, so it must not leak to a user
     * who cannot see that account in the first place.
     */
    private List<CloudAccount> accessibleAccounts(ClientUserDetails userDetails) {
        Long impersonatedUserId = ImpersonationContext.getImpersonatedUserId();
        Long targetClientId = userDetails.getClientId();

        if (impersonatedUserId != null) {
            Optional<User> impersonated = userRepository.findById(impersonatedUserId);
            if (impersonated.isPresent() && impersonated.get().getClient() != null) {
                targetClientId = impersonated.get().getClient().getId();
            }
        }

        boolean isAdmin = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role)
                        || "ROLE_XAMOPS_ADMIN".equals(role)
                        || "ROLE_SUPER_ADMIN".equals(role));

        if (isAdmin && impersonatedUserId == null) {
            return cloudAccountRepository.findAll();
        }

        Long userId = impersonatedUserId != null ? impersonatedUserId : userDetails.getId();
        List<Long> mappedAccountIds = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(userId);
        if (!mappedAccountIds.isEmpty()) {
            return cloudAccountRepository.findAllById(mappedAccountIds);
        }

        Optional<User> user = userRepository.findById(userId);
        if (user.isPresent() && user.get().getCreatedBy() == null && targetClientId != null) {
            return cloudAccountRepository.findByClientId(targetClientId);
        }
        return new ArrayList<>();
    }
}
