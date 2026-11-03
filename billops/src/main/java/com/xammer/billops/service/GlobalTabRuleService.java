package com.xammer.billops.service;

import com.xammer.billops.domain.GlobalTabRule;
import com.xammer.billops.domain.GlobalTabRuleException;
import com.xammer.billops.dto.GlobalTabRuleDTO;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.GlobalTabRuleRepository;
import com.xammer.cloud.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Platform-wide visibility rules for XamOps pages: hide a module/tab from every ROLE_XAMOPS user,
 * or show it to all of them, with per-user exceptions. See {@link GlobalTabRule} for semantics.
 *
 * <p>Only the write side lives here. xamops-service reads the same tables when it builds
 * {@code /api/xamops/team/profile}, so a change shows up on each user's next page load.
 */
@Service
public class GlobalTabRuleService {

    private static final Logger log = LoggerFactory.getLogger(GlobalTabRuleService.class);

    /** Catalog keys are lowercase kebab-case ("observability", "obs-logs"). */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,99}$");

    /** Exceptions are for a handful of users; anything larger is better done as the opposite rule. */
    static final int MAX_EXCEPTIONS_PER_RULE = 500;

    private static final String XAMOPS_ROLE = "ROLE_XAMOPS";

    @Autowired
    private GlobalTabRuleRepository ruleRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    /**
     * Every rule. With {@code visibleClientIds} non-null, exceptions are limited to users of those
     * clients, so an account-scoped admin does not see other customers' users.
     */
    @Transactional(readOnly = true)
    public List<GlobalTabRuleDTO> listRules(Set<Long> visibleClientIds) {
        return ruleRepository.findAllWithExceptions().stream()
                .map(rule -> toDto(rule, visibleClientIds))
                .toList();
    }

    /**
     * Creates the rule or changes its mode. Switching between HIDE and GRANT drops the exceptions:
     * an exception to "hide" keeps the page, an exception to "show" does not get it, so carrying
     * the list across would silently invert what each listed user sees.
     */
    @Transactional
    public GlobalTabRuleDTO setMode(String tabKey, String modeValue, String actor) {
        validateKey(tabKey);
        GlobalTabRule.Mode mode = parseMode(modeValue);

        GlobalTabRule rule = ruleRepository.findByTabKey(tabKey).orElse(null);
        if (rule == null) {
            rule = new GlobalTabRule(tabKey, mode);
        } else if (rule.getMode() != mode) {
            if (!rule.getExceptions().isEmpty()) {
                log.info("Global tab rule '{}' switched {} -> {}; cleared {} exception(s)",
                        tabKey, rule.getMode(), mode, rule.getExceptions().size());
            }
            rule.getExceptions().clear();
            rule.setMode(mode);
        }
        touch(rule, actor);
        GlobalTabRule saved = ruleRepository.save(rule);
        log.info("Global tab rule '{}' set to {} by {}", tabKey, mode, actor);
        return toDto(saved, null);
    }

    /** Removes the rule and its exceptions; the page goes back to per-user access. No-op if absent. */
    @Transactional
    public void clearRule(String tabKey, String actor) {
        validateKey(tabKey);
        ruleRepository.findByTabKey(tabKey).ifPresent(rule -> {
            ruleRepository.delete(rule);
            log.info("Global tab rule '{}' ({}) cleared by {}", tabKey, rule.getMode(), actor);
        });
    }

    @Transactional
    public GlobalTabRuleDTO addException(String tabKey, Long userId, String actor) {
        GlobalTabRule rule = requireRule(tabKey);
        if (userId == null) {
            throw new IllegalArgumentException("userId is required.");
        }
        boolean alreadyListed = rule.getExceptions().stream()
                .anyMatch(e -> userId.equals(e.getUser().getId()));
        if (alreadyListed) {
            return toDto(rule, null);
        }
        if (rule.getExceptions().size() >= MAX_EXCEPTIONS_PER_RULE) {
            throw new IllegalArgumentException("A rule can have at most " + MAX_EXCEPTIONS_PER_RULE + " exceptions.");
        }
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User " + userId + " not found."));
        if (!XAMOPS_ROLE.equals(user.getRole())) {
            // Rules only ever apply to XamOps users, so an exception for anyone else would do nothing.
            throw new IllegalArgumentException("Only XamOps users can be added as exceptions.");
        }
        rule.getExceptions().add(new GlobalTabRuleException(rule, user));
        touch(rule, actor);
        log.info("Global tab rule '{}': user {} added as exception by {}", tabKey, userId, actor);
        return toDto(ruleRepository.save(rule), null);
    }

    @Transactional
    public GlobalTabRuleDTO removeException(String tabKey, Long userId, String actor) {
        GlobalTabRule rule = requireRule(tabKey);
        boolean removed = rule.getExceptions().removeIf(e -> e.getUser().getId().equals(userId));
        if (removed) {
            touch(rule, actor);
            log.info("Global tab rule '{}': user {} removed from exceptions by {}", tabKey, userId, actor);
        }
        return toDto(ruleRepository.save(rule), null);
    }

    // ------------------------------------------------------------------ helpers

    private GlobalTabRule requireRule(String tabKey) {
        validateKey(tabKey);
        return ruleRepository.findByTabKey(tabKey)
                .orElseThrow(() -> new NoSuchElementException("No rule is set for '" + tabKey + "'."));
    }

    private static void validateKey(String tabKey) {
        if (tabKey == null || !KEY_PATTERN.matcher(tabKey).matches()) {
            throw new IllegalArgumentException("Invalid page key.");
        }
    }

    private static GlobalTabRule.Mode parseMode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("mode is required (HIDE or GRANT).");
        }
        try {
            return GlobalTabRule.Mode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be HIDE or GRANT.");
        }
    }

    private static void touch(GlobalTabRule rule, String actor) {
        rule.setUpdatedBy(actor);
        rule.setUpdatedAt(LocalDateTime.now());
    }

    private static GlobalTabRuleDTO toDto(GlobalTabRule rule, Set<Long> visibleClientIds) {
        List<GlobalTabRuleDTO.ExceptionUser> exceptions = rule.getExceptions().stream()
                .map(GlobalTabRuleException::getUser)
                .filter(u -> visibleClientIds == null
                        || (u.getClient() != null && visibleClientIds.contains(u.getClient().getId())))
                .map(u -> new GlobalTabRuleDTO.ExceptionUser(
                        u.getId(),
                        u.getUsername(),
                        u.getEmail(),
                        u.getClient() != null ? u.getClient().getName() : null))
                .sorted(Comparator.comparing(GlobalTabRuleDTO.ExceptionUser::username,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        return new GlobalTabRuleDTO(rule.getTabKey(), rule.getMode().name(),
                rule.getUpdatedBy(), rule.getUpdatedAt(), exceptions);
    }
}
