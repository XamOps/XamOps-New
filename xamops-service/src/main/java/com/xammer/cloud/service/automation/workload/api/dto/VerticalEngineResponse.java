package com.xammer.cloud.service.automation.workload.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The response body of {@code GET /api/eks/automation/vertical/{cluster}}.
 *
 * <p>Field names match the CloudK8s UI design's provisional contract (§11.5) exactly; the additions
 * ({@code executionPath}, {@code executionPathDetail}, and per-workload {@code provenance},
 * {@code horizontal} and {@code autoApplyEligible}) are additive, so a UI built against §11.5 keeps
 * working.
 *
 * @param engine      engine status; never fabricated
 * @param gateReasons shared reason codes, without the {@code K8S_} prefix
 * @param workloads   evaluated workloads; <strong>empty</strong> rather than placeholder-filled
 *                    whenever the engine cannot evaluate
 */
public record VerticalEngineResponse(EngineStatus engine, List<String> gateReasons,
                                     List<VerticalWorkloadDto> workloads) {

    public VerticalEngineResponse {
        gateReasons = gateReasons == null ? List.of() : List.copyOf(gateReasons);
        workloads = workloads == null ? List.of() : List.copyOf(workloads);
    }

    /**
     * The same response with {@code more} gate reasons appended after the engine's own, skipping
     * any already present. Used for the caller's write gates, which only the controller can know.
     */
    public VerticalEngineResponse withGateReasons(List<String> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        List<String> merged = new java.util.ArrayList<>(gateReasons);
        for (String reason : more) {
            if (reason != null && !merged.contains(reason)) {
                merged.add(reason);
            }
        }
        return new VerticalEngineResponse(engine, merged, workloads);
    }

    /**
     * @param enabled             {@code xamops.automation.vertical.enabled}
     * @param ready               {@code xamops.k8s.automation.readiness.vertical-rightsizing}
     * @param clusterAccess       {@code CONFIGURED | NOT_CONFIGURED | UNREACHABLE}, or
     *                            <strong>null when the engine has not checked</strong> — which is
     *                            the honest answer while the master switch is off, rather than
     *                            telling a customer to fix access that may already be correct
     * @param executionPath       {@code DIRECT | AGENT | NONE}
     * @param executionPathDetail why each path is or is not available
     * @param metricsSource       the usage source
     * @param lastCycle           the last reconciliation; always empty here, because no scheduler
     *                            exists yet
     */
    public record EngineStatus(boolean enabled,
                               boolean ready,
                               String clusterAccess,
                               String executionPath,
                               ExecutionPathDetail executionPathDetail,
                               MetricsSource metricsSource,
                               LastCycle lastCycle) {
    }

    /**
     * Lets the UI explain to a private-endpoint customer why direct mode is unavailable and what the
     * agent path needs, using only codes the UI's reason map already contains.
     */
    public record ExecutionPathDetail(PathAvailability direct, AgentAvailability agent) {
    }

    /** @param reason a shared reason code, or null when available or not yet checked */
    public record PathAvailability(boolean available, String reason) {
    }

    /** @param lastSeenAt the cluster config's last agent check-in, or null */
    public record AgentAvailability(boolean available, String reason, Instant lastSeenAt) {
    }

    /** @param name the provider's name, or null when nothing can supply usage */
    public record MetricsSource(boolean reachable, String name) {
    }

    public record LastCycle(Instant at, Integer evaluated, String error) {

        public static LastCycle never() {
            return new LastCycle(null, null, null);
        }
    }
}
