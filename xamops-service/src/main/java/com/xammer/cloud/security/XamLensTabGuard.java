package com.xammer.cloud.security;

import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Server-side enforcement of the {@code secops-xamlens} sidebar tab on every customer DSPM endpoint
 * (design 4.4 and 5.10, with BUILD-BRIEF section 13 resolution 1).
 *
 * <p>The guard mirrors the frontend's {@code makeXamTabGate} for {@code ROLE_XAMOPS} principals, the
 * only ones the frontend gates: {@code XamTabAccessContext} and {@code XamTabRouteGuard} apply the tab
 * rules only when the user has {@code ROLE_XAMOPS}, and {@code Sidebar.tsx} shows the XamOps nav,
 * XamLens included, to {@code ROLE_SUPER_ADMIN} without any tab rule. Every other principal therefore
 * passes with no repository read, so nobody sees XamLens in the sidebar and then gets a 403 from its
 * API. That includes a {@code ROLE_SUPER_ADMIN} team member whose own tab list leaves XamLens out.
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
        boolean gated = user.getAuthorities().stream()
                .map(authority -> K8sAutomationAccessService.normalizeRole(authority.getAuthority()))
                .anyMatch(GATED_ROLE::equals);
        if (!gated) {
            return;
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
