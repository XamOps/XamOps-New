package com.xammer.cloud.security;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Single place to answer "may this user touch this CloudAccount?".
 *
 * The rules here are deliberately identical to
 * {@code AccountHealthController.accessibleAccounts} and
 * {@code AccountManagerController}:
 *
 * <ol>
 *   <li>Platform admins (SUPER_ADMIN / XAMOPS_ADMIN / BILLOPS_ADMIN) may reach any account.</li>
 *   <li>A user with explicit rows in {@code user_cloud_account_access} is restricted to
 *       exactly those accounts — an explicit grant list is a whitelist, not an addition
 *       to the client-wide default.</li>
 *   <li>A primary ("root") user, i.e. one with no {@code createdBy}, implicitly holds
 *       access to every account belonging to their own client.</li>
 *   <li>Everyone else: denied.</li>
 * </ol>
 *
 * Callers that run outside a servlet request thread (WebSocket handlers, async tasks)
 * cannot rely on {@code ImpersonationContext}, which is a ThreadLocal populated by
 * {@code ImpersonationFilter} during the HTTP request. Those callers get the rules above
 * evaluated against the real authenticated user, which is the conservative choice.
 *
 * TODO: migrate AccountHealthController and AccountManagerController onto this service so
 * the rule lives in exactly one place. Left alone for now to avoid changing their behaviour.
 */
@Service
public class CloudAccountAccessService {

    private static final Logger log = LoggerFactory.getLogger(CloudAccountAccessService.class);

    private final UserRepository userRepository;
    private final UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    private final CloudAccountRepository cloudAccountRepository;

    public CloudAccountAccessService(UserRepository userRepository,
            UserCloudAccountAccessRepository userCloudAccountAccessRepository,
            CloudAccountRepository cloudAccountRepository) {
        this.userRepository = userRepository;
        this.userCloudAccountAccessRepository = userCloudAccountAccessRepository;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Resolves a CloudAccount by primary key and asserts the caller may use it.
     *
     * <p>
     * The AutoSpotting controllers take {@code accountId} as a path variable and passed it
     * straight to the service layer, so any authenticated user — and before the
     * SecurityConfig change, any unauthenticated one — could drive spot infrastructure in
     * another tenant's cloud account by incrementing an integer. This is the single
     * chokepoint those endpoints now go through.
     *
     * <p>
     * "Not found" and "not yours" both raise {@link AccessDeniedException} with the same
     * message on purpose. Distinguishing them turns the endpoint into an oracle for which
     * account IDs exist, which is most of the value of the original IDOR.
     *
     * @throws AccessDeniedException if the caller is absent, the account does not exist, or
     *                               the caller is not entitled to it.
     */
    public CloudAccount requireAccess(ClientUserDetails userDetails, Long cloudAccountId) {
        if (userDetails == null) {
            // Reachable if an endpoint is left in the permitAll list by mistake; failing
            // closed here means the guard does not depend on SecurityConfig being right.
            throw new AccessDeniedException("Authentication required.");
        }
        if (cloudAccountId == null) {
            throw new AccessDeniedException("Account not found or access denied.");
        }

        CloudAccount account = cloudAccountRepository.findById(cloudAccountId).orElse(null);
        if (account == null || !canAccess(userDetails, account)) {
            log.warn("Denied account access: user={} requested cloudAccountId={}",
                    userDetails.getId(), cloudAccountId);
            throw new AccessDeniedException("Account not found or access denied.");
        }
        return account;
    }

    /**
     * String-identifier variant, for the GCP and Azure endpoints whose {@code accountId} is
     * either a provider account identifier (GCP project, Azure subscription) or a numeric
     * primary key.
     *
     * <p>
     * The resolution order matches {@code GcpAutoSpottingService.resolveAccount} so guarded
     * endpoints keep accepting the identifiers the frontend already sends. The difference is
     * that the result is then checked against the caller's entitlement, and that the numeric
     * fallback cannot be used to reach an account the caller does not hold.
     *
     * @throws AccessDeniedException if the identifier resolves to nothing the caller may use.
     */
    public CloudAccount requireAccessByIdentifier(ClientUserDetails userDetails, String identifier) {
        if (userDetails == null) {
            throw new AccessDeniedException("Authentication required.");
        }
        if (identifier == null || identifier.isBlank()) {
            throw new AccessDeniedException("Account not found or access denied.");
        }

        CloudAccount byProvider = cloudAccountRepository.findByProviderAccountId(identifier).orElse(null);
        if (byProvider != null && canAccess(userDetails, byProvider)) {
            return byProvider;
        }

        // Numeric fallback, preserved from the original resolver.
        try {
            CloudAccount byId = cloudAccountRepository.findById(Long.parseLong(identifier)).orElse(null);
            if (byId != null && canAccess(userDetails, byId)) {
                return byId;
            }
        } catch (NumberFormatException ignored) {
            // Not a primary key; the provider lookup above was the only option.
        }

        log.warn("Denied account access: user={} requested account identifier", userDetails.getId());
        throw new AccessDeniedException("Account not found or access denied.");
    }

    /**
     * @return true only if {@code userDetails} is entitled to operate on {@code account}.
     *         Fails closed on any missing input.
     */
    public boolean canAccess(ClientUserDetails userDetails, CloudAccount account) {
        if (userDetails == null || account == null || account.getId() == null) {
            return false;
        }

        if (isPlatformAdmin(userDetails)) {
            return true;
        }

        Long userId = userDetails.getId();
        if (userId == null) {
            return false;
        }

        List<Long> mappedAccountIds = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(userId);
        if (!mappedAccountIds.isEmpty()) {
            // An explicit grant list is exhaustive — no client-wide fallback.
            return mappedAccountIds.contains(account.getId());
        }

        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty() || user.get().getCreatedBy() != null) {
            // Sub-user with no explicit grants holds no account access.
            return false;
        }

        Long callerClientId = userDetails.getClientId();
        Long accountClientId = account.getClient() != null ? account.getClient().getId() : null;
        if (callerClientId == null || accountClientId == null) {
            log.warn("Denying account access: unresolved client (caller={}, account={})",
                    callerClientId, accountClientId);
            return false;
        }
        return callerClientId.equals(accountClientId);
    }

    private boolean isPlatformAdmin(ClientUserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_SUPER_ADMIN".equals(role)
                        || "ROLE_XAMOPS_ADMIN".equals(role)
                        || "ROLE_BILLOPS_ADMIN".equals(role));
    }
}
