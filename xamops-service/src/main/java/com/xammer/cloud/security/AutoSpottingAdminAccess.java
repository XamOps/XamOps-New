package com.xammer.cloud.security;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Conditions on the AutoSpotting admin reads ({@code /api/xamops/autospotting-admin/**}) on top
 * of the admin roles named in {@code AutoSpottingAdminController}'s {@code @PreAuthorize}.
 *
 * <p>These reads return aggregates across every customer. Before G1 they ended in a 404 for
 * everyone (the old path was routed to billops), so neither denial below removes access anyone
 * had (P02-R3, P02-R8):
 * <ul>
 *   <li><b>Login-As.</b> While impersonation is active, the effective principal must be a super
 *       admin. {@link ImpersonationContext} is how the request thread knows: it is set by
 *       {@code ImpersonationJwtFilter} for a billops Login-As token and by
 *       {@code ImpersonationFilter} for the X-Impersonate-User header. billops copies the target
 *       user's real role into a Login-As token and downgrades only ROLE_SUPER_ADMIN, so without
 *       this a BillOps sub-admin who logs in as a BillOps or XamOps admin reads these
 *       aggregates.</li>
 *   <li><b>Demo accounts.</b> The shared demo logins skip OTP ({@code LoginOtpController}) and
 *       demo.billops is seeded as ROLE_BILLOPS_ADMIN. They are denied whatever role they hold.
 *       The demo frontend mocks these paths; this stops a direct API call.</li>
 * </ul>
 * A normal, non-impersonated session with one of the admin roles is unaffected.
 */
@Component("autoSpottingAdminAccess")
public class AutoSpottingAdminAccess {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingAdminAccess.class);

    /**
     * The OTP-exempt demo accounts, by email. Must equal {@code LoginOtpController.DEMO_EMAILS};
     * {@code AutoSpottingAdminControllerTest} fails if the two drift apart.
     */
    public static final Set<String> DEMO_EMAILS = Set.of("demo.xamops@xammer.in", "demo.billops@xammer.in");

    private final UserRepository userRepository;
    private final DemoClientMembership demoClientMembership;

    public AutoSpottingAdminAccess(UserRepository userRepository, DemoClientMembership demoClientMembership) {
        this.userRepository = userRepository;
        this.demoClientMembership = demoClientMembership;
    }

    /** Whether {@code authentication}, which already holds an admin role, may read the aggregates. */
    public boolean permits(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        Long impersonatedUserId = ImpersonationContext.getImpersonatedUserId();
        if (impersonatedUserId != null && !hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
            log.warn("Denied AutoSpotting admin read: Login-As session without ROLE_SUPER_ADMIN "
                    + "(impersonated user id {})", impersonatedUserId);
            return false;
        }
        if (isDemoAccount(authentication)) {
            log.warn("Denied AutoSpotting admin read: demo account");
            return false;
        }
        return true;
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** The principal's own user record decides; its name is checked too in case it is an email. */
    private boolean isDemoAccount(Authentication authentication) {
        if (isDemoEmail(authentication.getName())) {
            return true;
        }
        boolean idBearing = authentication.getPrincipal() instanceof ClientUserDetails details
                && details.getId() != null;
        Optional<User> user = idBearing
                ? userRepository.findById(((ClientUserDetails) authentication.getPrincipal()).getId())
                : userRepository.findByUsername(authentication.getName());
        if (user.map(User::getEmail).filter(AutoSpottingAdminAccess::isDemoEmail).isPresent()) {
            return true;
        }
        // Additive client-is-demo check: a host-provisioned demo-client user (always a ClientUserDetails
        // carrying an id) is denied the cross-customer aggregates too. The findByUsername branch is left
        // untouched by design — demo users are never resolved through it (see class Javadoc).
        return idBearing
                && demoClientMembership.isDemoClientUser(((ClientUserDetails) authentication.getPrincipal()).getId());
    }

    private static boolean isDemoEmail(String value) {
        return value != null && DEMO_EMAILS.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
