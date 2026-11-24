package com.xammer.cloud.service.autospotting.core;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The AutoSpotting confirm/revert authorization matrix, in both modes (design-core.md §11, PO-14;
 * plan-phase-11.md task 11.4). The working rule is the requirements default
 * ({@code autospotting.authz.mode=DEFAULT}, the code default) until the PO accepts the recommendation
 * in writing (po-decisions.md records the default; PO-14 "accepted in writing" is the merge gate).
 * {@code PO14_RECOMMENDED} is implemented and UNLIFTED: it is selected only after sign-off.
 *
 * <p>DEFAULT (po-decisions.md PO-14, design-core.md §11 table):
 * <ul>
 *   <li><b>Client owner</b> ({@code createdBy} null, client owns the account): reads and confirms
 *       everything, revert included;</li>
 *   <li><b>Sub-user</b> with an explicit account grant: reads only; any confirm is 403
 *       {@code AS_FORBIDDEN_ROLE};</li>
 *   <li><b>Platform admin</b> (SUPER_ADMIN/XAMOPS_ADMIN/BILLOPS_ADMIN) in their own session: confirms
 *       REVERT only, with a {@code reason} (422 {@code CHECK_FAILED} without), audited and named in
 *       the revert notice; every non-REVERT confirm is 403;</li>
 *   <li><b>Impersonated</b> (Login-As) session: reads only; any confirm is 403.</li>
 * </ul>
 *
 * <p>PO14_RECOMMENDED: any client user with access may confirm, except a sub-user without
 * {@code autospotting_write}; platform admins and impersonated sessions may revert, and may enable
 * only with a {@code reason}, which writes an {@code ADMIN_ENABLED} outbox row (delivery lands in
 * Phase 24, so the caller records the intent and audit now).
 *
 * <p>Read authorization (tenancy) is enforced earlier by {@code CloudAccountAccessService}; this
 * service decides only whether the authenticated actor may <em>confirm</em> a plan of a given kind.
 */
@Service
public class AutoSpottingAuthorization {

    /** The configured authorization mode (design-core.md §11, PO-14). */
    public enum Mode {
        /** The requirements default (po-decisions.md PO-14): the code default. */
        DEFAULT,
        /** The recommendation; set only after PO sign-off (UNLIFTED in this phase). */
        PO14_RECOMMENDED
    }

    private final UserRepository userRepository;
    private final UserCloudAccountAccessRepository accessRepository;
    private final Mode mode;

    public AutoSpottingAuthorization(UserRepository userRepository,
                                     UserCloudAccountAccessRepository accessRepository,
                                     @Value("${autospotting.authz.mode:DEFAULT}") String mode) {
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.mode = parseMode(mode);
    }

    public Mode mode() {
        return mode;
    }

    private static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Mode.DEFAULT;
        }
        try {
            return Mode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // An unknown value fails safe to the strict default (never the looser recommendation).
            return Mode.DEFAULT;
        }
    }

    /** The classified role of the caller for this account (the §11 rows). */
    public enum Role {
        CLIENT_OWNER,
        SUB_USER,
        PLATFORM_ADMIN,
        IMPERSONATED,
        /** Not entitled at all (should not reach here after the tenancy guard). */
        NONE
    }

    /**
     * The outcome of an authorization decision. {@code allowed} says whether the confirm may proceed;
     * {@code actor} is the audit actor to record (impersonation carries both identities);
     * {@code adminEnabled} marks a PO14_RECOMMENDED admin/impersonated ENABLE that must write an
     * {@code ADMIN_ENABLED} outbox row; {@code denyCode}/{@code denyStatus}/{@code denyDetail} carry
     * the refusal when {@code allowed} is false.
     */
    public record Decision(boolean allowed, Role role, Actor actor, boolean adminEnabled,
                           String denyCode, HttpStatus denyStatus, String denyDetail) {

        static Decision allow(Role role, Actor actor, boolean adminEnabled) {
            return new Decision(true, role, actor, adminEnabled, null, null, null);
        }

        static Decision deny(Role role, String code, HttpStatus status, String detail) {
            return new Decision(false, role, null, false, code, status, detail);
        }
    }

    /**
     * Decide whether {@code userDetails} may confirm a plan of {@code kind} on {@code account}, given
     * the supplied {@code reason} (nullable). Throws {@link AutoSpottingApiException} on refusal so
     * the caller can let it propagate, or inspect the returned {@link Decision} when it needs the
     * audit actor and the {@code ADMIN_ENABLED} flag.
     */
    public Decision authorizeConfirm(ClientUserDetails userDetails, CloudAccount account,
                                     OperationKind kind, String reason) {
        Role role = classify(userDetails, account);
        boolean revert = kind == OperationKind.REVERT;
        boolean hasReason = reason != null && !reason.isBlank();

        return switch (mode) {
            case DEFAULT -> decideDefault(userDetails, role, revert, hasReason);
            case PO14_RECOMMENDED -> decideRecommended(userDetails, account, role, revert, hasReason);
        };
    }

    private Decision decideDefault(ClientUserDetails user, Role role, boolean revert, boolean hasReason) {
        return switch (role) {
            case CLIENT_OWNER -> Decision.allow(role, ownerActor(user), false);
            case PLATFORM_ADMIN -> {
                if (!revert) {
                    yield Decision.deny(role, "AS_FORBIDDEN_ROLE", HttpStatus.FORBIDDEN,
                            "A platform admin may confirm a revert only.");
                }
                if (!hasReason) {
                    yield Decision.deny(role, "CHECK_FAILED", HttpStatus.UNPROCESSABLE_ENTITY,
                            "A reason is required when a platform admin confirms a revert.");
                }
                yield Decision.allow(role, adminActor(user), false);
            }
            case SUB_USER, IMPERSONATED, NONE -> Decision.deny(role, "AS_FORBIDDEN_ROLE",
                    HttpStatus.FORBIDDEN, "You are not allowed to confirm this plan.");
        };
    }

    private Decision decideRecommended(ClientUserDetails user, CloudAccount account, Role role,
                                       boolean revert, boolean hasReason) {
        return switch (role) {
            case CLIENT_OWNER -> Decision.allow(role, ownerActor(user), false);
            case SUB_USER -> {
                if (!hasAutospottingWrite(user, account)) {
                    yield Decision.deny(role, "AS_FORBIDDEN_ROLE", HttpStatus.FORBIDDEN,
                            "You do not have AutoSpotting write access to this account.");
                }
                yield Decision.allow(role, ownerActor(user), false);
            }
            case PLATFORM_ADMIN, IMPERSONATED -> {
                if (revert) {
                    yield Decision.allow(role, actorFor(role, user), false);
                }
                // Enable (any non-revert confirm) only with a reason; writes an ADMIN_ENABLED notice.
                if (!hasReason) {
                    yield Decision.deny(role, "CHECK_FAILED", HttpStatus.UNPROCESSABLE_ENTITY,
                            "A reason is required to enable on a customer's behalf.");
                }
                yield Decision.allow(role, actorFor(role, user), true);
            }
            case NONE -> Decision.deny(role, "AS_FORBIDDEN_ROLE", HttpStatus.FORBIDDEN,
                    "You are not allowed to confirm this plan.");
        };
    }

    // ── actor classification ───────────────────────────────────────────────────

    /** Classify the caller against this account (impersonation first, then admin, then owner/sub-user). */
    public Role classify(ClientUserDetails userDetails, CloudAccount account) {
        if (userDetails == null || account == null) {
            return Role.NONE;
        }
        if (ImpersonationContext.getImpersonatedUserId() != null) {
            return Role.IMPERSONATED;
        }
        if (isPlatformAdmin(userDetails)) {
            return Role.PLATFORM_ADMIN;
        }
        Long userId = userDetails.getId();
        if (userId == null) {
            return Role.NONE;
        }
        List<Long> grants = accessRepository.findCloudAccountIdsByUserId(userId);
        if (!grants.isEmpty()) {
            // An explicit grant list is exhaustive: this is a sub-user (CloudAccountAccessService F-06).
            return grants.contains(account.getId()) ? Role.SUB_USER : Role.NONE;
        }
        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty() || user.get().getCreatedBy() != null) {
            // A sub-user with no grants holds no access.
            return Role.NONE;
        }
        Long callerClientId = userDetails.getClientId();
        Long accountClientId = account.getClient() != null ? account.getClient().getId() : null;
        if (callerClientId != null && callerClientId.equals(accountClientId)) {
            return Role.CLIENT_OWNER;
        }
        return Role.NONE;
    }

    private boolean hasAutospottingWrite(ClientUserDetails userDetails, CloudAccount account) {
        Long userId = userDetails.getId();
        if (userId == null || account.getId() == null) {
            return false;
        }
        return accessRepository.findByUserId(userId).stream()
                .anyMatch(a -> a.getCloudAccount() != null
                        && account.getId().equals(a.getCloudAccount().getId())
                        && a.isAutospottingWrite());
    }

    private Actor actorFor(Role role, ClientUserDetails user) {
        return role == Role.IMPERSONATED ? impersonatedActor(user) : adminActor(user);
    }

    private Actor ownerActor(ClientUserDetails user) {
        return Actor.user(user.getId(), primaryRole(user));
    }

    private Actor adminActor(ClientUserDetails user) {
        return Actor.operator(user.getId(), primaryRole(user));
    }

    private Actor impersonatedActor(ClientUserDetails user) {
        // The impersonated identity acts; the operator (the real session user) is recorded alongside
        // (AC-SPN-10). user.getId() is the impersonated user resolved into the session principal.
        return Actor.impersonated(user.getId(), primaryRole(user), ImpersonationContext.getImpersonatedUserId());
    }

    private static boolean isPlatformAdmin(ClientUserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_SUPER_ADMIN".equals(role)
                        || "ROLE_XAMOPS_ADMIN".equals(role)
                        || "ROLE_BILLOPS_ADMIN".equals(role));
    }

    private static String primaryRole(ClientUserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse(null);
    }
}
