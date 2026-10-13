package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.workload.api.AutomationWritePermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the <strong>system actor</strong> the auto-apply loop applies as: a real, stored
 * primary/root {@link User} of the owning tenant that clears the same {@link AutomationWritePermission}
 * gate a human clears.
 *
 * <h2>Why a real stored user, and not a synthetic principal</h2>
 *
 * {@code VerticalApplyService.apply} runs {@link AutomationWritePermission#decide}, which loads the
 * {@code app_user} row by {@code principal.getId()} and re-checks the email against the demo set, the
 * role against {@link AutomationWritePermission#WRITE_ROLES}, the impersonation context, and the tab
 * grants. A made-up principal with no backing row is refused by that gate (no row → NO_USER_PERMISSION).
 * So the loop has to act as an identity that genuinely passes it — the same identity, re-checked —
 * rather than a label that bypasses it. This resolver finds one; if none exists the cluster is skipped.
 *
 * <h2>Which user</h2>
 *
 * The candidate is a primary/root user of the client ({@code createdBy == null}, matching
 * {@code CloudAccountAccessService}'s rule for implicit client-wide account access), whose normalised
 * role is in {@link AutomationWritePermission#WRITE_ROLES}, and whose email is not one of
 * {@link AutomationWritePermission#DEMO_EMAILS}. The lowest id is chosen so the choice is deterministic
 * across cycles. The built {@link ClientUserDetails} carries that user's id, so the gate re-derives
 * everything from the same row the human path would.
 *
 * <h2>Hibernate session note</h2>
 *
 * The loop runs on a {@code @Scheduled} thread with no open session, so this method is
 * {@link Transactional} (read-only): {@link UserRepository#findByClientId} is read and every field this
 * resolver touches ({@code id}, {@code role}, {@code email}) is a non-lazy scalar read inside that
 * transaction, so no LAZY association is traversed off the scheduled thread.
 */
@Service
public class SystemActorResolver {

    private static final Logger logger = LoggerFactory.getLogger(SystemActorResolver.class);

    private final UserRepository userRepository;

    public SystemActorResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The system actor for one tenant, or empty when the tenant has no primary user that could
     * change automation — in which case the loop skips the cluster rather than inventing an identity.
     *
     * @param clientId the owning tenant's {@code client.id}
     */
    @Transactional(readOnly = true)
    public Optional<ClientUserDetails> resolve(Long clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        Optional<User> candidate = userRepository.findByClientId(clientId).stream()
                .filter(user -> user.getCreatedBy() == null)
                .filter(SystemActorResolver::hasWriteRole)
                .filter(SystemActorResolver::isNotDemo)
                .min(java.util.Comparator.comparing(User::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        if (candidate.isEmpty()) {
            logger.info("No primary automation-capable user for client {}; auto-apply skips its clusters",
                    clientId);
            return Optional.empty();
        }
        User user = candidate.get();
        String authority = normalisedRole(user);
        return Optional.of(new ClientUserDetails(
                user.getUsername(),
                "[PROTECTED]",
                List.of(new SimpleGrantedAuthority(authority)),
                clientId,
                user.getId(),
                null));
    }

    private static boolean hasWriteRole(User user) {
        return AutomationWritePermission.WRITE_ROLES.contains(normalisedRole(user));
    }

    private static boolean isNotDemo(User user) {
        String email = user.getEmail() == null ? "" : user.getEmail().trim().toLowerCase(Locale.ROOT);
        return !AutomationWritePermission.DEMO_EMAILS.contains(email);
    }

    /**
     * The row's role with the {@code ROLE_} prefix it may be missing — the same normalisation
     * {@code AutomationWritePermission} and {@code ImpersonationFilter} apply, replicated here
     * because that method is package-private to the {@code api} package and the gate must stay
     * untouched. {@code User}'s default role is the bare {@code "USER"}.
     */
    private static String normalisedRole(User user) {
        String role = user.getRole() == null ? "" : user.getRole().trim().toUpperCase(Locale.ROOT);
        if (role.isEmpty()) {
            return "";
        }
        return role.startsWith("ROLE_") ? role : "ROLE_" + role;
    }
}
