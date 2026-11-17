package com.xammer.cloud.security;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server-side enforcement of the {@code secops-xamlens} sidebar tab on every customer DSPM endpoint
 * (design 4.4 and 5.10, with BUILD-BRIEF section 13 resolution 1).
 *
 * <p>The guard is an allow-list of the two roles that {@code Sidebar.tsx} shows the XamOps nav, XamLens
 * included, to. Roles are compared after {@code K8sAutomationAccessService.normalizeRole}, so a stored
 * {@code XAMOPS} counts as {@code ROLE_XAMOPS}.
 * <ul>
 *   <li>{@code ROLE_XAMOPS} gets the tab rules below, the only role {@code XamTabAccessContext} and
 *       {@code XamTabRouteGuard} gate. It is checked first, as {@code isXamops} is in the frontend.</li>
 *   <li>{@code ROLE_SUPER_ADMIN} passes with no repository read, even a team member whose own tab list
 *       leaves XamLens out, because the sidebar applies no tab rule to it.</li>
 *   <li>Every other role, {@code ROLE_BILLOPS} and {@code ROLE_BILLOPS_ADMIN} included, never sees
 *       XamLens and is refused with no repository read.</li>
 * </ul>
 * Outside header impersonation, the roles that see XamLens are exactly the ones admitted, so nobody
 * sees XamLens in the sidebar and then gets a 403 from its API.
 *
 * <p>Under impersonation the principal is the target user (design 5.10):
 * <ul>
 *   <li>Header impersonation ({@code X-Impersonate-User}, {@code ImpersonationFilter}) started by a
 *       {@code ROLE_SUPER_ADMIN} passes first, with no repository read, whatever the target's role or tab
 *       rules (user decision: super admins see all of XamLens when viewing as any user). The "view as
 *       user" switch keeps the admin's own roles in the browser, so the sidebar shows XamLens, and the API
 *       now agrees. Tenancy is unchanged: the data is the target's client, raw sample values included.
 *       {@code ImpersonationFilter} marks such a request in {@link ImpersonationContext}, judged on the
 *       request's own authentication, and the mark counts only for the principal it installed.</li>
 *   <li>Header impersonation started by anyone else ({@code ROLE_BILLOPS_ADMIN}) gets the target's role
 *       and tab rules.</li>
 *   <li>BillOps Login-As ({@code ImpersonationJwtFilter}): the JWT carries the target's role into the
 *       browser too, so the sidebar and this guard both gate on the target. That filter never sets the
 *       mark.</li>
 * </ul>
 *
 * <p>For a gated principal the decision follows {@code makeXamTabGate.isTabAllowed}:
 * <ol>
 *   <li>the platform-wide rule on {@code secops-xamlens} itself (HIDE beats GRANT on the same key):
 *       HIDE denies, GRANT allows;</li>
 *   <li>otherwise the rule on the module {@code secops}, in the same way;</li>
 *   <li>otherwise the user's own tab list: empty means unrestricted, else it must contain
 *       {@code secops-xamlens}.</li>
 * </ol>
 * A missing principal or principal id fails closed, and a repository exception propagates (500), so
 * the guard never allows by accident. The frontend route guard is UX only; this is the enforcement.
 */
@Component
public class XamLensTabGuard {

    private static final Logger log = LoggerFactory.getLogger(XamLensTabGuard.class);

    /** The XamLens sidebar tab. */
    public static final String TAB_KEY = "secops-xamlens";

    /** The SecOps module the tab belongs to. */
    public static final String MODULE_KEY = "secops";

    /** The only role {@code XamTabAccessContext} gates: {@code isXamops = hasAnyRole('ROLE_XAMOPS')}. */
    static final String GATED_ROLE = "ROLE_XAMOPS";

    /** The other role that sees XamLens; {@code Sidebar.tsx} applies no tab rule to it. */
    static final String UNGATED_ROLE = "ROLE_SUPER_ADMIN";

    private final GlobalTabRuleReader globalTabRuleReader;
    private final UserTabAccessRepository userTabAccessRepository;

    public XamLensTabGuard(GlobalTabRuleReader globalTabRuleReader, UserTabAccessRepository userTabAccessRepository) {
        this.globalTabRuleReader = globalTabRuleReader;
        this.userTabAccessRepository = userTabAccessRepository;
    }

    /**
     * Returns when the caller may open XamLens.
     *
     * @throws XamLensAccessDeniedException when it may not, answered as 403 {@code TAB_ACCESS_DENIED}.
     */
    public void require(ClientUserDetails user) {
        if (user == null || user.getId() == null) {
            log.debug("[XamLens] tab access denied: no principal id");
            throw new XamLensAccessDeniedException();
        }
        if (ImpersonationContext.isStartedBySuperAdmin()
                && user.getId().equals(ImpersonationContext.getImpersonatedUserId())) {
            log.debug("[XamLens] tab access allowed for user {}: super-admin view as user", user.getId());
            return;
        }
        Set<String> roles = user.getAuthorities().stream()
                .map(authority -> K8sAutomationAccessService.normalizeRole(authority.getAuthority()))
                .collect(Collectors.toSet());
        if (!roles.contains(GATED_ROLE)) {
            if (roles.contains(UNGATED_ROLE)) {
                return;
            }
            log.debug("[XamLens] tab access denied for user {}: no role that sees XamLens", user.getId());
            throw new XamLensAccessDeniedException();
        }

        Long userId = user.getId();
        GlobalTabRuleReader.UserRules rules = globalTabRuleReader.rulesFor(userId);
        Rule rule = ruleFor(rules, TAB_KEY);
        if (rule == Rule.NONE) {
            rule = ruleFor(rules, MODULE_KEY);
        }
        if (rule == Rule.GRANT) {
            return;
        }
        if (rule == Rule.NONE) {
            List<String> tabs = userTabAccessRepository.findTabNamesByUserId(userId);
            if (tabs.isEmpty() || tabs.contains(TAB_KEY)) {
                return;
            }
        }
        log.debug("[XamLens] tab access denied for user {}", userId);
        throw new XamLensAccessDeniedException();
    }

    private enum Rule { HIDE, GRANT, NONE }

    /** The platform-wide rule on one key; HIDE beats GRANT, as in {@code makeXamTabGate.ownRule}. */
    private static Rule ruleFor(GlobalTabRuleReader.UserRules rules, String key) {
        if (rules.hidden().contains(key)) {
            return Rule.HIDE;
        }
        return rules.granted().contains(key) ? Rule.GRANT : Rule.NONE;
    }
}
