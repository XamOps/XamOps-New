package com.xammer.cloud.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.K8sRefusalBody;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runs before every handler under {@code /api/eks/autoscaling/**}, for every HTTP method
 * (design §6.6 steps 1-8, plan P9). Registered by {@code K8sAutomationWebConfig.GUARDED_PATHS}.
 *
 * <ol>
 *   <li>The principal must be a {@link ClientUserDetails}, else {@code 401 K8S_UNAUTHENTICATED}
 *       (Spring Security already requires authentication; this is the fail-closed backup).</li>
 *   <li>The cluster path variable ({@code clusterName}, or {@code cluster}, the vertical engine's
 *       name for it) must follow the EKS name rule, and {@code accountId} must be 1-12 digits,
 *       else {@code 400 K8S_INVALID_REQUEST} with {@code field}.</li>
 *   <li>{@link K8sAutomationAccessService#resolve}: {@code 403 K8S_NO_ACCOUNT_ACCESS} when nothing
 *       is reachable, with one message for unknown and not-yours.</li>
 *   <li>A cluster without a config is {@code 404 K8S_CLUSTER_NOT_REGISTERED} for every method.</li>
 *   <li>The resolved {@link K8sAutomationRequestContext} is stored as a request attribute.</li>
 *   <li>{@code GET}/{@code HEAD}/{@code OPTIONS} stop here; every other method is a mutation and
 *       must pass {@link K8sAutomationAccessService#writeDecision}.</li>
 * </ol>
 *
 * <p>
 * Refusals are written here as {@link K8sRefusalBody} JSON and logged in {@code preHandle},
 * because Spring doesn't call {@code afterCompletion} for a handler whose {@code preHandle}
 * returned false. Mutations that reach their handler are logged in {@code afterCompletion}
 * with the final status. No log line or body carries request input that failed validation, or
 * an exception's message.
 */
@Component
public class K8sAutomationGuardInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(K8sAutomationGuardInterceptor.class);

    /** EKS cluster name rule (design §13). */
    public static final Pattern CLUSTER_NAME = Pattern.compile("^[0-9A-Za-z][A-Za-z0-9_-]{0,99}$");

    /** AWS account number as the UI sends it. */
    public static final Pattern ACCOUNT_ID = Pattern.compile("^[0-9]{1,12}$");

    /** Path variables that name the cluster: ours, then the vertical engine's (contract §2). */
    static final List<String> CLUSTER_VARIABLES = List.of("clusterName", "cluster");

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Marks a mutation that passed the guard, so {@code afterCompletion} logs it. */
    static final String MUTATION_ATTRIBUTE = K8sAutomationGuardInterceptor.class.getName() + ".mutation";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final K8sAutomationAccessService accessService;

    public K8sAutomationGuardInterceptor(K8sAutomationAccessService accessService) {
        this.accessService = accessService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // A CORS preflight carries no credentials and runs no handler code; Spring answers it.
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof ClientUserDetails user)) {
            return refuse(request, response, null, null, null,
                    K8sAutomationRefusedException.of(K8sAutomationReason.UNAUTHENTICATED));
        }

        Map<String, String> variables = uriVariables(request);
        String clusterVariable = CLUSTER_VARIABLES.stream().filter(variables::containsKey).findFirst()
                .orElse(CLUSTER_VARIABLES.get(0));
        String cluster = variables.get(clusterVariable);
        if (cluster == null || !CLUSTER_NAME.matcher(cluster).matches()) {
            return refuse(request, response, user, null, null, K8sAutomationRefusedException.of(
                    K8sAutomationReason.INVALID_REQUEST, "The cluster name isn't valid.", clusterVariable));
        }
        String accountId = request.getParameter("accountId");
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            return refuse(request, response, user, cluster, null, K8sAutomationRefusedException.of(
                    K8sAutomationReason.INVALID_REQUEST, "accountId must be a 1-12 digit AWS account number.",
                    "accountId"));
        }

        K8sAutomationRequestContext context;
        try {
            context = accessService.resolve(user, accountId, cluster);
        } catch (K8sAutomationRefusedException e) {
            return refuse(request, response, user, cluster, null, e);
        } catch (RuntimeException e) {
            log.error("K8s automation guard: resolving cluster {} failed for user {}", cluster, user.getId(), e);
            return refuse(request, response, user, cluster, null, K8sAutomationRefusedException.of(
                    K8sAutomationReason.INTERNAL,
                    READ_METHODS.contains(method) ? K8sAutomationReason.INTERNAL.defaultMessage()
                            : K8sAutomationReason.INTERNAL_MUTATION_MESSAGE));
        }

        if (context.config() == null) {
            return refuse(request, response, user, cluster, context.primaryAccount(),
                    K8sAutomationRefusedException.of(K8sAutomationReason.CLUSTER_NOT_REGISTERED));
        }
        request.setAttribute(K8sAutomationRequestContext.ATTRIBUTE, context);

        if (READ_METHODS.contains(method)) {
            return true;
        }

        Optional<K8sAutomationRefusedException> refusal;
        try {
            // Every /api/eks/autoscaling/** mutation is a node-automation write (BP-9 / OQ-6): the
            // guard evaluates the write decision in the NODE_AUTOMATION scope so a cluster that has
            // completed Enable-automation and is verified fresh may reach the path on a read-only
            // connection, while a still-read-only cluster is refused exactly as before.
            refusal = accessService.writeDecision(user, context.gateAccounts(), context.config(),
                    K8sAutomationAccessService.GateScope.NODE_AUTOMATION).firstRefusal();
        } catch (RuntimeException e) {
            log.error("K8s automation guard: write decision failed for user {} on cluster {}", user.getId(), cluster, e);
            refusal = Optional.of(K8sAutomationRefusedException.of(
                    K8sAutomationReason.INTERNAL, K8sAutomationReason.INTERNAL_MUTATION_MESSAGE));
        }
        if (refusal.isPresent()) {
            request.removeAttribute(K8sAutomationRequestContext.ATTRIBUTE);
            return refuse(request, response, user, cluster, context.primaryAccount(), refusal.get());
        }
        request.setAttribute(MUTATION_ATTRIBUTE, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        if (!Boolean.TRUE.equals(request.getAttribute(MUTATION_ATTRIBUTE))) {
            return;
        }
        K8sAutomationRequestContext context =
                (K8sAutomationRequestContext) request.getAttribute(K8sAutomationRequestContext.ATTRIBUTE);
        CloudAccount account = context != null ? context.primaryAccount() : null;
        log.info("K8s automation mutation: user={} impersonatedUser={} account={} cluster={} method={} path={} status={}",
                context != null ? context.user().getId() : null,
                ImpersonationContext.getImpersonatedUserId(),
                account != null ? account.getId() : null,
                context != null && context.config() != null ? context.config().getClusterName() : null,
                request.getMethod(), request.getRequestURI(), response.getStatus());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, String> uriVariables(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return attribute instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    /**
     * Writes the refusal body, logs it, and stops the chain. {@code cluster} is only ever a value
     * that passed validation, so the log can't be forged through it.
     */
    private static boolean refuse(HttpServletRequest request, HttpServletResponse response, ClientUserDetails user,
                                  String cluster, CloudAccount account, K8sAutomationRefusedException refusal)
            throws IOException {
        K8sAutomationReason reason = refusal.getReason();
        int status = refusal.getStatus().value();
        String line = "K8s automation refused: user={} impersonatedUser={} account={} cluster={} method={} path={} "
                + "status={} code={} field={}";
        Object[] args = {
                user != null ? user.getId() : null,
                ImpersonationContext.getImpersonatedUserId(),
                account != null ? account.getId() : null,
                cluster,
                request.getMethod(),
                request.getRequestURI(),
                status,
                reason.code(),
                refusal.getField()};
        if (reason == K8sAutomationReason.NO_ACCOUNT_ACCESS || reason == K8sAutomationReason.UNAUTHENTICATED) {
            log.warn(line, args);
        } else {
            log.info(line, args);
        }

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(K8sRefusalBody.of(refusal)));
        return false;
    }
}
