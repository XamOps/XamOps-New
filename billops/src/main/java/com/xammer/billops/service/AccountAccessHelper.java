package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    /**
     * Returns the set of allowed CloudAccount DB IDs for the current user,
     * or null if the user is a primary admin (no restriction applies).
     */
    public Set<Long> getAllowedDbIds() {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            AppUser currentUser = appUserRepository.findById(userId).orElse(null);
            if (currentUser == null || currentUser.getCreatedBy() == null) {
                return null; // Primary admin — no restriction
            }
            if (currentUser.isAllAccountsAccess()) {
                return null; // Sub-user with all-accounts access — no restriction (dynamic)
            }
            List<Long> ids = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(userId);
            return new HashSet<>(ids);
        } catch (Exception e) {
            return null; // Security context not available — no restriction
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
