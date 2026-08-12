package com.xammer.cloud.security;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public CloudAccountAccessService(UserRepository userRepository,
            UserCloudAccountAccessRepository userCloudAccountAccessRepository) {
        this.userRepository = userRepository;
        this.userCloudAccountAccessRepository = userCloudAccountAccessRepository;
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
