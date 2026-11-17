package com.xammer.cloud.config.multitenancy;

import java.io.Serializable;

/**
 * The operator behind an impersonated request, set as the impersonated Authentication's details.
 * {@code MattersAuditService} records it as the actor of the XamLens reads made on the request.
 * <ul>
 *   <li>{@link ImpersonationFilter} sets it for a super admin's or a BillOps admin's "view as user"
 *       ({@code X-Impersonate-User}), from the request's own authentication.</li>
 *   <li>{@link ImpersonationJwtFilter} sets it for BillOps Login-As, from the token's actor claims.</li>
 * </ul>
 * It rides on the Authentication rather than in a ThreadLocal, so a copied security context
 * ({@code MattersDspmService}'s profile pool) carries it too. It only names the operator: nothing reads it
 * for an access decision.
 *
 * @param userId   the operator's id; {@link ImpersonationJwtFilter#ACTOR_UNKNOWN} for a Login-As token
 *                 that has no {@code actorUserId} claim, and null when an admin's principal is not a
 *                 {@code ClientUserDetails}
 * @param username null for a Login-As token that has no {@code actorUsername} claim
 * @param role     null for a Login-As token that has no {@code actorRole} claim
 */
public record ImpersonatingOperator(Long userId, String username, String role) implements Serializable {
}
