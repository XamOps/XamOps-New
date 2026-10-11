package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Whether the signed-in user may <strong>change</strong> CloudK8s automation.
 *
 * <h2>This predicate is shared with another branch on purpose</h2>
 *
 * It is the CloudK8s UI revamp design's decision D2, which that branch implements as
 * {@code K8sAutomationAccessService}. That class is not on {@code main}, so it cannot be imported;
 * this is the <strong>same rule, implemented locally</strong>, and the two are a deliberate
 * <em>reconciliation point</em> — when both branches land, one of them should delete its copy. The
 * rule is not re-litigated here: a different predicate would mean the same user could change
 * automation on one screen and not the other.
 *
 * <h2>Everything is read from the database row, not from the session</h2>
 *
 * The principal is used for exactly one thing: its id. Email, role and tab grants come from the
 * {@code app_user} row and {@code user_tab_access}, because:
 *
 * <ul>
 *   <li>the shared demo identities' <strong>usernames</strong> are "Xammer Admin" and "Xammer
 *       BillOps", so a username comparison would never match and the OTP-free shared login would
 *       pass every gate as an ordinary user of the first client. The <strong>email</strong> is the
 *       only field that identifies them;</li>
 *   <li>{@code CustomUserDetailsService} grants the raw DB role value and {@code User}'s default is
 *       {@code "USER"}, so the role needs the same {@code ROLE_} normalisation
 *       {@code ImpersonationFilter} applies.</li>
 * </ul>
 *
 * <p>A principal with no id, or whose row cannot be loaded, is <strong>refused</strong> and logged.
 *
 * <h2>The five rules, in this order</h2>
 *
 * <ol>
 *   <li>{@link CloudAccountAccessService#canAccess} — else {@code K8S_NO_ACCOUNT_ACCESS}. The
 *       controller has already enforced entitlement before reaching here; this is the same question
 *       asked again so the service is safe on its own.</li>
 *   <li>The row's email is not one of {@link #DEMO_EMAILS} — else {@code K8S_DEMO}. The shared demo
 *       identity must not be able to patch a real cluster.</li>
 *   <li>The request is not an impersonated session — else {@code K8S_NO_USER_PERMISSION}. Both
 *       impersonation paths replace the principal with the target user, so without this rule a
 *       BillOps admin could change a customer's automation by signing in as that customer.</li>
 *   <li>The normalised role is a XamOps-product role ({@link #WRITE_ROLES}) — else
 *       {@code K8S_NO_USER_PERMISSION}. BillOps-only principals reach every account through
 *       {@code canAccess}, and changing a customer's cluster automation is not a billing task.</li>
 *   <li>The user has no {@code user_tab_access} rows (unrestricted), or holds <strong>both</strong>
 *       {@link #TAB_CLOUDK8S} and {@link #TAB_CLOUDK8S_AUTOMATION} — else
 *       {@code K8S_NO_USER_PERMISSION}.</li>
 * </ol>
 */
@Component
public class AutomationWritePermission {

    private static final Logger logger = LoggerFactory.getLogger(AutomationWritePermission.class);

    /**
     * The shared, OTP-free demo identities, by <strong>email</strong>.
     *
     * <p>Mirrors the private set in {@code LoginOtpController}, which owns the login flow and stays
     * untouched. {@code AutomationWritePermissionTest} reads that field by reflection and fails if
     * the two ever drift.
     */
    public static final Set<String> DEMO_EMAILS =
            Set.of("demo.xamops@xammer.in", "demo.billops@xammer.in");

    /** Roles that may change automation. BillOps-only principals are deliberately absent. */
    public static final Set<String> WRITE_ROLES = Set.of(
            "ROLE_XAMOPS", "ROLE_XAMOPS_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_USER");

    /** The existing CloudK8s sidebar key. */
    public static final String TAB_CLOUDK8S = "cloudops-cloudk8s";

    /**
     * The automation-write permission key. Stored in {@code user_tab_access} like a tab key but not
     * a sidebar tab; the UI branch adds the nested checkbox that grants it.
     */
    public static final String TAB_CLOUDK8S_AUTOMATION = "cloudops-cloudk8s-automation";

    private final CloudAccountAccessService accessService;
    private final UserRepository userRepository;
    private final UserTabAccessRepository tabAccessRepository;
    private final Supplier<Long> impersonatedUserId;

    @Autowired
    public AutomationWritePermission(CloudAccountAccessService accessService,
                                     UserRepository userRepository,
                                     UserTabAccessRepository tabAccessRepository) {
        this(accessService, userRepository, tabAccessRepository,
                ImpersonationContext::getImpersonatedUserId);
    }

    /** @param impersonatedUserId the seam the impersonation rule is tested through */
    AutomationWritePermission(CloudAccountAccessService accessService,
                              UserRepository userRepository,
                              UserTabAccessRepository tabAccessRepository,
                              Supplier<Long> impersonatedUserId) {
        this.accessService = accessService;
        this.userRepository = userRepository;
        this.tabAccessRepository = tabAccessRepository;
        this.impersonatedUserId = impersonatedUserId;
    }

    /** @return permitted, or the refusal with the code and the copy that explains it */
    public Decision decide(ClientUserDetails principal, CloudAccount account) {
        if (principal == null || principal.getId() == null) {
            logger.warn("Refusing an automation write: the principal carries no user id");
            return refusedPermission(Detail.NO_PRINCIPAL,
                    "Sign in again to change automation settings.");
        }
        if (!accessService.canAccess(principal, account)) {
            logger.warn("Refusing an automation write: user {} is not entitled to the account",
                    principal.getId());
            return new Decision(false, null, Detail.ACCOUNT,
                    "Account not found or access denied.", "K8S_NO_ACCOUNT_ACCESS");
        }

        Optional<User> row = userRepository.findById(principal.getId());
        if (row.isEmpty()) {
            logger.warn("Refusing an automation write: no app_user row for id {}", principal.getId());
            return refusedPermission(Detail.NO_PRINCIPAL,
                    "Sign in again to change automation settings.");
        }
        User user = row.get();

        String email = user.getEmail() == null ? "" : user.getEmail().trim().toLowerCase(Locale.ROOT);
        if (DEMO_EMAILS.contains(email)) {
            return new Decision(false, ReasonCode.DEMO, Detail.DEMO,
                    "This is the shared demo identity, so nothing is applied to a real cluster.",
                    ReasonCode.DEMO.refusalCode());
        }
        if (impersonatedUserId.get() != null) {
            return refusedPermission(Detail.IMPERSONATION,
                    "Automation changes aren't available while you're signed in as another user."
                            + " Return to your own session to change automation.");
        }
        if (!WRITE_ROLES.contains(normalisedRole(user))) {
            return refusedPermission(Detail.ROLE,
                    "Your role can view automation but not change it.");
        }
        List<String> tabs = tabAccessRepository.findTabNamesByUserId(user.getId());
        if (!tabs.isEmpty()
                && !(tabs.contains(TAB_CLOUDK8S) && tabs.contains(TAB_CLOUDK8S_AUTOMATION))) {
            return refusedPermission(Detail.TAB_GRANT,
                    "You don't have permission to change automation. Ask your XamOps administrator"
                            + " to allow automation changes for your user.");
        }
        return new Decision(true, null, null, null, null);
    }

    /**
     * The row's role with the {@code ROLE_} prefix it may be missing.
     *
     * <p>{@code User}'s default is the bare {@code "USER"} and {@code CustomUserDetailsService}
     * grants the raw value, so the same normalisation {@code ImpersonationFilter} performs has to
     * happen here or a perfectly ordinary user would be refused.
     */
    static String normalisedRole(User user) {
        String role = user.getRole() == null ? "" : user.getRole().trim().toUpperCase(Locale.ROOT);
        if (role.isEmpty()) {
            return "";
        }
        return role.startsWith("ROLE_") ? role : "ROLE_" + role;
    }

    private static Decision refusedPermission(String detail, String message) {
        return new Decision(false, ReasonCode.NO_USER_PERMISSION, detail, message,
                ReasonCode.NO_USER_PERMISSION.refusalCode());
    }

    /** Which rule refused, for the log and for the UI branch's copy selection. */
    public static final class Detail {

        public static final String ACCOUNT = "ACCOUNT";
        public static final String DEMO = "DEMO";
        public static final String IMPERSONATION = "IMPERSONATION";
        public static final String ROLE = "ROLE";
        public static final String TAB_GRANT = "TAB_GRANT";
        public static final String NO_PRINCIPAL = "NO_PRINCIPAL";

        private Detail() {
        }
    }

    /**
     * @param reason       the shared gate code, or null when the refusal is not a gate
     * @param detail       which rule refused; logged, and the key the UI branch picks copy by
     * @param refusalCode  the exact {@code code} the refusal body must carry
     */
    public record Decision(boolean permitted, ReasonCode reason, String detail, String message,
                           String refusalCode) {

        public Decision {
            if (!permitted && (refusalCode == null || message == null)) {
                throw new IllegalArgumentException("a refusal must carry a code and a message");
            }
        }
    }
}
