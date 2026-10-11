package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.K8sRefusalBody;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.security.K8sAutomationRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

/**
 * Shared pieces of the CloudK8s controllers' local exception handlers (design §6.6 "Refusal
 * format"): one refusal body, fixed messages, the same audit fields the guard logs, and the
 * offending JSON field taken from Jackson's path. Never echoes an exception message.
 */
final class K8sAutomationResponses {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private K8sAutomationResponses() {
    }

    static ResponseEntity<K8sRefusalBody> refusal(K8sAutomationRefusedException e) {
        return ResponseEntity.status(e.getStatus()).body(K8sRefusalBody.of(e));
    }

    static ResponseEntity<K8sRefusalBody> refusal(K8sAutomationReason reason, String message, String field) {
        return refusal(K8sAutomationRefusedException.of(reason, message, field));
    }

    static boolean isRead(HttpServletRequest request) {
        return request.getMethod() != null && READ_METHODS.contains(request.getMethod().toUpperCase());
    }

    /** {@code 500 K8S_INTERNAL} with the fixed read or mutation message. */
    static ResponseEntity<K8sRefusalBody> internal(HttpServletRequest request) {
        return refusal(K8sAutomationReason.INTERNAL,
                isRead(request) ? K8sAutomationReason.INTERNAL.defaultMessage()
                        : K8sAutomationReason.INTERNAL_MUTATION_MESSAGE,
                null);
    }

    /** Logs a refusal raised inside a handler with the guard's audit fields. */
    static void logRefusal(Logger log, HttpServletRequest request, K8sAutomationRefusedException e) {
        Object attribute = request.getAttribute(K8sAutomationRequestContext.ATTRIBUTE);
        K8sAutomationRequestContext ctx = attribute instanceof K8sAutomationRequestContext c ? c : null;
        CloudAccount account = ctx != null ? ctx.primaryAccount() : null;
        String line = "K8s automation refused: user={} impersonatedUser={} account={} cluster={} method={} path={} "
                + "status={} code={} field={}";
        Object[] args = {
                ctx != null ? ctx.user().getId() : null,
                ImpersonationContext.getImpersonatedUserId(),
                account != null ? account.getId() : null,
                ctx != null && ctx.config() != null ? ctx.config().getClusterName() : null,
                request.getMethod(),
                request.getRequestURI(),
                e.getStatus().value(),
                e.getReason().code(),
                e.getField()};
        if (e.getStatus().value() == 400) {
            log.warn(line, args);
        } else {
            log.info(line, args);
        }
    }

    /**
     * The JSON path of a body binding failure, for example {@code targetUtilizationCpu} or
     * {@code criticalNamespaces[2]}, from Jackson 2 or Jackson 3, or {@code null}.
     */
    static String jsonField(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof com.fasterxml.jackson.databind.JsonMappingException j2) {
                return path(j2.getPath().stream()
                        .map(r -> new Ref(r.getFieldName(), r.getIndex())).toList());
            }
            if (t instanceof tools.jackson.core.JacksonException j3) {
                return path(j3.getPath().stream()
                        .map(r -> new Ref(r.getPropertyName(), r.getIndex())).toList());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private record Ref(String name, int index) {
    }

    private static String path(List<Ref> refs) {
        StringBuilder out = new StringBuilder();
        for (Ref ref : refs) {
            if (ref.name() != null) {
                if (!out.isEmpty()) {
                    out.append('.');
                }
                out.append(ref.name());
            } else if (ref.index() >= 0) {
                out.append('[').append(ref.index()).append(']');
            }
        }
        return out.isEmpty() ? null : out.toString();
    }
}
