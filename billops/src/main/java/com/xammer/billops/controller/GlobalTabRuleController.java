package com.xammer.billops.controller;

import com.xammer.billops.dto.GlobalTabRuleDTO;
import com.xammer.billops.service.AdminAccessScope;
import com.xammer.billops.service.GlobalTabRuleService;
import com.xammer.cloud.domain.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Platform-wide XamOps page visibility, managed from the BillOps admin User Manager.
 *
 * <ul>
 *   <li>{@code GET    /}                            rules with their exceptions</li>
 *   <li>{@code PUT    /{tabKey}}                    body {@code {"mode":"HIDE"|"GRANT"}}</li>
 *   <li>{@code DELETE /{tabKey}}                    back to per-user access</li>
 *   <li>{@code POST   /{tabKey}/exceptions}         body {@code {"userId":123}}</li>
 *   <li>{@code DELETE /{tabKey}/exceptions/{userId}}</li>
 * </ul>
 *
 * <p>A rule reaches every customer, so changing one needs an admin with no cloud-account scope
 * ({@link AdminAccessScope} returns null). A scoped admin can read the rules, with exceptions
 * limited to the users of their own clients.
 */
@RestController
@RequestMapping("/api/billops/admin/xamops-page-rules")
@PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
public class GlobalTabRuleController {

    private static final String SCOPED_ADMIN_MESSAGE =
            "Only admins with access to every account can change platform-wide page visibility.";

    @Autowired
    private GlobalTabRuleService service;

    @Autowired
    private AdminAccessScope adminAccessScope;

    @GetMapping
    public GlobalTabRuleDTO.ListResponse list() {
        boolean canEdit = adminAccessScope.accountIdsForCurrentUser() == null;
        return new GlobalTabRuleDTO.ListResponse(canEdit,
                service.listRules(canEdit ? null : adminAccessScope.clientIdsForCurrentUser()));
    }

    @PutMapping("/{tabKey}")
    public ResponseEntity<?> setMode(@PathVariable String tabKey, @RequestBody GlobalTabRuleDTO.ModeRequest body) {
        return write(() -> ResponseEntity.ok(
                service.setMode(tabKey, body != null ? body.mode() : null, actor())));
    }

    @DeleteMapping("/{tabKey}")
    public ResponseEntity<?> clear(@PathVariable String tabKey) {
        return write(() -> {
            service.clearRule(tabKey, actor());
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/{tabKey}/exceptions")
    public ResponseEntity<?> addException(@PathVariable String tabKey,
                                          @RequestBody GlobalTabRuleDTO.ExceptionRequest body) {
        return write(() -> ResponseEntity.ok(
                service.addException(tabKey, body != null ? body.userId() : null, actor())));
    }

    @DeleteMapping("/{tabKey}/exceptions/{userId}")
    public ResponseEntity<?> removeException(@PathVariable String tabKey, @PathVariable Long userId) {
        return write(() -> ResponseEntity.ok(service.removeException(tabKey, userId, actor())));
    }

    /** Scope check plus the error mapping shared by every write. */
    private ResponseEntity<?> write(Supplier<ResponseEntity<?>> action) {
        if (adminAccessScope.accountIdsForCurrentUser() != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", SCOPED_ADMIN_MESSAGE));
        }
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            // Two admins creating the same rule at once: the unique key on tab_key rejects the second.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "This page was changed by someone else just now. Refresh and try again."));
        }
    }

    private String actor() {
        AppUser user = adminAccessScope.currentUser();
        if (user != null) {
            return user.getUsername();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }
}
