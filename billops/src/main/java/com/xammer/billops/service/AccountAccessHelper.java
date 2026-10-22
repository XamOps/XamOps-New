package com.xammer.billops.service;

import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.util.SecurityUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Centralises sub-user account access resolution so every service
 * can share the same "am I a sub-user, and if so which accounts can I see?" logic.
 *
 * Return contract:
 *   null  → current user is a primary admin (no restriction; show everything)
 *   Set   → current user is a sub-user; only show items belonging to these IDs
 *           (an empty Set means "no accounts assigned → show nothing")
 */
@Service
public class AccountAccessHelper {

    private static final Logger log = LoggerFactory.getLogger(AccountAccessHelper.class);

    @PostConstruct
    public void init() {
        log.info("✅ AccountAccessHelper v2 (ROLE_BILLOPS_ADMIN_SUB scoping) initialized");
    }

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private AdminAccessScope adminAccessScope;

    /**
     * Returns the set of allowed CloudAccount DB IDs for the current user,
     * or null if the user is a primary admin (no restriction applies).
     */
    public Set<Long> getAllowedDbIds() {
        try {
            Long userId = SecurityUtils.getCurrentUserId();

            // Resolve effective role from Security context — always authoritative
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String effectiveRole = (auth != null) ? auth.getAuthorities().stream()
                    .map(a -> a.getAuthority())
                    .filter(a -> a.startsWith("ROLE_"))
                    .findFirst().orElse(null) : null;

            // ROLE_BILLOPS_ADMIN_SUB — handle BEFORE looking up the AppUser by ID,
            // because these users may not have a record in the tenant app_user table.
            if ("ROLE_BILLOPS_ADMIN_SUB".equals(effectiveRole)) {
                // Try to find the AppUser by ID first, then fall back to username
                AppUser subAdmin = appUserRepository.findById(userId).orElse(null);
                if (subAdmin == null && auth != null) {
                    subAdmin = appUserRepository.findByUsername(auth.getName()).orElse(null);
                    log.debug("[AccountAccessHelper] SUB_ADMIN not found by ID {}, username lookup: {}",
                            userId, subAdmin != null ? "found id=" + subAdmin.getId() : "not found");
                }

                if (subAdmin != null && subAdmin.isAllAccountsAccess()) {
                    log.debug("[AccountAccessHelper] SUB_ADMIN has allAccountsAccess → no restriction");
                    return null;
                }

                if (subAdmin == null) {
                    log.debug("[AccountAccessHelper] SUB_ADMIN not found in app_user table → restricting to empty");
                    return Collections.emptySet();
                }

                return adminAccessScope.resolveAccountIds(subAdmin);
            }

            // For all other roles: look up the AppUser
            AppUser currentUser = appUserRepository.findById(userId).orElse(null);

            // Other admin roles: the account list assigned in the BillOps admin User Manager
            // narrows them to exactly those accounts. This is the case the primary-admin check
            // below would otherwise swallow — a primary admin returned null and was handed
            // everything regardless of what had been selected for them. Without a grant list this
            // resolves to null and that path applies unchanged.
            //
            // Resolved with its own username fallback rather than reassigning currentUser, whose
            // id is what the sub-user lookup at the bottom of this method keys off.
            if (effectiveRole != null && effectiveRole.contains("BILLOPS_ADMIN")) {
                AppUser admin = currentUser;
                if (admin == null && auth != null) {
                    admin = appUserRepository.findByUsername(auth.getName()).orElse(null);
                }
                Set<Long> adminScope = adminAccessScope.resolveAccountIds(admin);
                if (adminScope != null) {
                    return adminScope;
                }
            }

            log.debug("[AccountAccessHelper] userId={} effectiveRole={} dbRole={} createdBy={} allAccess={}",
                    userId, effectiveRole,
                    currentUser != null ? currentUser.getRole() : "null",
                    currentUser != null ? currentUser.getCreatedBy() : "null",
                    currentUser != null ? currentUser.isAllAccountsAccess() : "null");

            if (currentUser == null) {
                log.debug("[AccountAccessHelper] user not found → no restriction");
                return null;
            }

            if (currentUser.getCreatedBy() == null) {
                return null; // Primary admin — no restriction
            }
            if (currentUser.isAllAccountsAccess()) {
                return null; // Sub-user with all-accounts access — no restriction (dynamic)
            }
            List<Long> ids = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(userId);
            return new HashSet<>(ids);
        } catch (Exception e) {
            log.error("[AccountAccessHelper] getAllowedDbIds failed — defaulting to no restriction. Cause: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Returns the set of cloud-provider-specific account identifiers
     * (e.g. AWS account IDs, GCP project IDs, Azure subscription IDs)
     * for the current sub-user, or null for a primary admin.
     */
    public Set<String> getAllowedProviderAccountIds() {
        Set<Long> dbIds = getAllowedDbIds();
        if (dbIds == null) return null; // Primary admin
        if (dbIds.isEmpty()) return Collections.emptySet();

        List<CloudAccount> accounts = cloudAccountRepository.findAllById(dbIds);
        return accounts.stream()
                .map(this::resolveProviderAccountId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Filters a list of CloudAccounts to only those visible to the current user.
     * Returns the original list unchanged for primary admins.
     */
    public List<CloudAccount> filterAccounts(List<CloudAccount> accounts) {
        Set<Long> allowedIds = getAllowedDbIds();
        if (allowedIds == null) return accounts; // Primary admin
        if (allowedIds.isEmpty()) return Collections.emptyList();
        return accounts.stream()
                .filter(a -> allowedIds.contains(a.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Returns the cloud-provider-specific account identifier for the given CloudAccount.
     */
    public String resolveProviderAccountId(CloudAccount account) {
        if (account == null) return null;
        String provider = account.getProvider();
        if ("AWS".equalsIgnoreCase(provider)) return account.getAwsAccountId();
        if ("GCP".equalsIgnoreCase(provider)) return account.getGcpProjectId();
        if ("Azure".equalsIgnoreCase(provider)) return account.getAzureSubscriptionId();
        return null;
    }
}
