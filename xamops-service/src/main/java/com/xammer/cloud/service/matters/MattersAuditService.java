package com.xammer.cloud.service.matters;

import com.xammer.cloud.domain.MattersAccessAudit;
import com.xammer.cloud.security.ClientUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Builds DSPM audit rows and stamps them with who acted, from where.
 *
 * <p>Row construction lives here; persistence lives in {@link MattersAuditWriter}. The split is not
 * decorative — see that class for why {@code @Async} cannot work on a method called from within its
 * own bean.
 *
 * <p>Read activity is written off-thread and operator mutations on-thread. The reasoning for each is
 * on the corresponding {@code MattersAuditWriter} method.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MattersAuditService {

    private final MattersAuditWriter writer;

    public static final String ACTOR_CUSTOMER = "CUSTOMER";
    public static final String ACTOR_OPERATOR = "OPERATOR";
    public static final String ACTOR_SYSTEM = "SYSTEM";

    /**
     * Records a successful credential use.
     *
     * @param endpoint Matters path only, never the query string — search terms are customer content
     *                 and do not belong in a long-retained table.
     */
    public void recordCredentialUse(Long clientId, String apiKeyId, String projectId,
                                    String endpoint, long durationMs) {
        MattersAccessAudit row = MattersAccessAudit.credentialUse(clientId, apiKeyId, projectId, endpoint);
        row.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
        stampActor(row);
        stampRequest(row);
        writer.writeAsync(row);
    }

    /** Records a failed credential use, carrying the {@link MattersApiException.Reason} name. */
    public void recordCredentialFailure(Long clientId, String apiKeyId, String projectId,
                                        String endpoint, String reason, String detail) {
        MattersAccessAudit row = MattersAccessAudit.credentialUse(clientId, apiKeyId, projectId, endpoint);
        row.failed(reason, detail);
        stampActor(row);
        stampRequest(row);
        writer.writeAsync(row);
    }

    /** Records an operator mutation. Written on this thread — see {@link MattersAuditWriter#writeSync}. */
    public void recordMutation(Long clientId, String action, String projectId, String detail) {
        MattersAccessAudit row = MattersAccessAudit.mutation(clientId, action, projectId);
        row.setDetail(detail);
        stampActor(row);
        stampRequest(row);
        writer.writeSync(row);
    }

    /** Records a failed operator mutation, e.g. a verification that did not connect. */
    public void recordMutationFailure(Long clientId, String action, String projectId,
                                      String reason, String detail) {
        MattersAccessAudit row = MattersAccessAudit.mutation(clientId, action, projectId);
        row.failed(reason, detail);
        stampActor(row);
        stampRequest(row);
        writer.writeSync(row);
    }

    /**
     * Fills in who acted, from the security context.
     *
     * The distinction that matters on review is {@code actorType}: a customer reading their own
     * inventory is routine, an operator reading it is the event worth looking at. That is derived
     * from the caller's role rather than from which endpoint was hit, so an operator who reaches
     * customer data through an impersonated session is still recorded as an operator.
     *
     * No authentication at all means a scheduled or internal caller, recorded as SYSTEM rather than
     * left blank, so an unattributed row is never ambiguous between "system" and "we failed to look".
     */
    private void stampActor(MattersAccessAudit row) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            row.setActorType(ACTOR_SYSTEM);
            return;
        }

        String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse(null);
        row.setActorRole(role);
        row.setActorUsername(auth.getName());

        if (auth.getPrincipal() instanceof ClientUserDetails principal) {
            row.setActorUserId(principal.getId());
            // An operator reading a customer's data is the case worth surfacing, so it is decided by
            // role, not by endpoint: the same read service serves both the customer page and an
            // impersonated operator session.
            boolean operator = role != null
                    && (role.contains("SUPER_ADMIN") || role.contains("BILLOPS_ADMIN"));
            row.setActorType(operator ? ACTOR_OPERATOR : ACTOR_CUSTOMER);

            // A principal whose own client differs from the client being read can only be an
            // operator acting across tenants — worth flagging even when the role check above
            // did not catch it.
            if (principal.getClientId() != null && row.getClientId() != null
                    && !principal.getClientId().equals(row.getClientId())) {
                row.setImpersonated(true);
                row.setActorType(ACTOR_OPERATOR);
            }
        } else {
            row.setActorType(ACTOR_SYSTEM);
        }
    }

    /**
     * Fills in caller IP and user agent when there is a request in scope.
     *
     * {@code X-Forwarded-For} is preferred and only its first entry is taken: the services sit behind
     * an ALB and nginx, so {@code getRemoteAddr} would record a load balancer on every row, and the
     * later entries in the header are proxies rather than the client.
     */
    private void stampRequest(MattersAccessAudit row) {
        currentRequest().ifPresent(req -> {
            String forwarded = req.getHeader("X-Forwarded-For");
            String ip = (forwarded != null && !forwarded.isBlank())
                    ? forwarded.split(",")[0].trim()
                    : req.getRemoteAddr();
            row.setIpAddress(truncate(ip, 64));
            row.setUserAgent(truncate(req.getHeader("User-Agent"), 512));
        });
    }

    private Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.ofNullable(attrs.getRequest());
        }
        return Optional.empty();
    }

    /** Guards the column widths — a long User-Agent would otherwise fail the insert. */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
