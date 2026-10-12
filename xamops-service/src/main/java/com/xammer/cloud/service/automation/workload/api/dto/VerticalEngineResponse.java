package com.xammer.cloud.service.automation.workload.api.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The response body of {@code GET /api/eks/automation/vertical/{cluster}}.
 *
 * <p>Field names match the CloudK8s UI design's provisional contract (§11.5) exactly; the additions
 * ({@code executionPath}, {@code executionPathDetail}, per-workload {@code provenance},
 * {@code horizontal} and {@code autoApplyEligible}, and {@code gateDetails} and
 * {@code writeGatesChecked}) are additive, so a UI built against §11.5 keeps working.
 *
 * <h2>Why {@code writeGatesChecked} exists</h2>
 *
 * The caller's write gates ({@code DEMO}, {@code NO_USER_PERMISSION}, {@code READ_ONLY_CONNECTION})
 * are reported by <em>presence</em> in {@code gateReasons}, so their absence used to be the only
 * signal that the caller may apply. A response that never evaluated them — a build from before they
 * were reported, which is exactly what a frontend deployed ahead of its backend talks to — looked
 * identical to "permitted", and the UI offered an Apply that endpoint 3 then refused. This flag is
 * the positive statement: true only when this response's {@code gateReasons} already include the
 * caller's write gates, so the UI can refuse to enable Apply on anything else.
 *
 * @param engine            engine status; never fabricated
 * @param gateReasons       shared reason codes, without the {@code K8S_} prefix
 * @param workloads         evaluated workloads; <strong>empty</strong> rather than
 *                          placeholder-filled whenever the engine cannot evaluate
 * @param gateDetails       for a gate whose rule has a finer name than its code, that name, keyed by
 *                          the gate code: {@code {"NO_USER_PERMISSION": "TAB_GRANT"}}. The same
 *                          value endpoint 3's refusal carries in {@code detail}
 * @param writeGatesChecked whether the caller's write gates were evaluated for this response
 */
public record VerticalEngineResponse(EngineStatus engine, List<String> gateReasons,
                                     List<VerticalWorkloadDto> workloads,
                                     Map<String, String> gateDetails,
                                     boolean writeGatesChecked) {

    public VerticalEngineResponse {
        gateReasons = gateReasons == null ? List.of() : List.copyOf(gateReasons);
        workloads = workloads == null ? List.of() : List.copyOf(workloads);
        // Insertion-ordered, so the wire order follows gateReasons' order.
        Map<String, String> details = new LinkedHashMap<>();
        if (gateDetails != null) {
            gateDetails.forEach((code, detail) -> {
                if (code != null && detail != null) {
                    details.put(code, detail);
                }
            });
        }
        gateDetails = Collections.unmodifiableMap(details);
    }

    /** The engine's own view, before the caller's write gates are known. */
    public VerticalEngineResponse(EngineStatus engine, List<String> gateReasons,
                                  List<VerticalWorkloadDto> workloads) {
        this(engine, gateReasons, workloads, Map.of(), false);
    }

    /**
     * The same response with the caller's write gates appended after the engine's own, skipping any
     * already present, their details merged in, and {@code writeGatesChecked} set. Only the
     * controller can know the caller, so only it calls this.
     */
    public VerticalEngineResponse withWriteGates(List<String> codes, Map<String, String> details) {
        List<String> merged = new ArrayList<>(gateReasons);
        for (String reason : codes == null ? List.<String>of() : codes) {
            if (reason != null && !merged.contains(reason)) {
                merged.add(reason);
            }
        }
        Map<String, String> mergedDetails = new LinkedHashMap<>(gateDetails);
        if (details != null) {
            details.forEach((code, detail) -> {
                if (code != null) {
                    mergedDetails.putIfAbsent(code, detail);
                }
            });
        }
        return new VerticalEngineResponse(engine, merged, workloads, mergedDetails, true);
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
