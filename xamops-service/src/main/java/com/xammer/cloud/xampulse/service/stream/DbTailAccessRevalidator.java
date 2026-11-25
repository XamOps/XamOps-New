package com.xammer.cloud.xampulse.service.stream;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Database-backed revalidation: the user must still exist, still hold a XamPulse viewer role, and still be entitled
 * to every account in the ticket. When impersonating, the real actor must still be a platform admin (the same rule
 * the impersonation filter applies). Any unexpected failure is UNKNOWN so a database blip does not end a session.
 */
@Component("xpTailAccessRevalidator")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class DbTailAccessRevalidator implements TailAccessRevalidator {

    /** The authorities {@code XamPulseRoles.VIEWER} accepts. */
    private static final Set<String> VIEWER_ROLES = Set.of("ROLE_XAMOPS", "ROLE_SUPER_ADMIN");
    private static final Set<String> IMPERSONATOR_ROLES = Set.of("ROLE_SUPER_ADMIN", "ROLE_BILLOPS_ADMIN");

    private final UserRepository users;
    private final CloudAccountAccessService access;

    public DbTailAccessRevalidator(UserRepository users, CloudAccountAccessService access) {
        this.users = users;
        this.access = access;
    }

    @Override
    public Verdict check(long effectiveUserId, Long actorUserId, List<Long> accountIds) {
        try {
            Optional<User> user = users.findById(effectiveUserId);
            if (user.isEmpty()) {
                return Verdict.DENIED;
            }
            String role = roleOf(user.get());
            if (!VIEWER_ROLES.contains(role)) {
                return Verdict.DENIED;
            }
            if (actorUserId != null) {
                Optional<User> actor = users.findById(actorUserId);
                if (actor.isEmpty() || !IMPERSONATOR_ROLES.contains(roleOf(actor.get()))) {
                    return Verdict.DENIED;
                }
            }
            ClientUserDetails details = new ClientUserDetails(user.get().getUsername(), null,
                    List.of(new SimpleGrantedAuthority(role)),
                    user.get().getClient() != null ? user.get().getClient().getId() : null,
                    user.get().getId(), null);
            for (Long accountId : accountIds) {
                try {
                    access.requireAccess(details, accountId);
                } catch (AccessDeniedException e) {
                    return Verdict.DENIED;
                }
            }
            return Verdict.ALLOWED;
        } catch (RuntimeException e) {
            return Verdict.UNKNOWN;
        }
    }

    private static String roleOf(User u) {
        String r = u.getRole();
        if (r == null) {
            return "";
        }
        return r.startsWith("ROLE_") ? r : "ROLE_" + r;
    }
}
