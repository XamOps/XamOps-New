package com.xammer.cloud.service.automation.workload.api.dto;

/**
 * Endpoint 2's {@code 200} body for a mode change:
 *
 * <pre>{@code { "mode": "AUTO", "policySource": "WORKLOAD_ROW" } }</pre>
 *
 * <p>It echoes the state the server actually saved, so the UI reflects the stored mode without a
 * second read: {@code mode} is the {@link com.xammer.cloud.service.automation.workload.WorkloadMode}
 * now in force, and {@code policySource} is {@code WORKLOAD_ROW} because a mode write always leaves a
 * stored row — the same {@code policySource} endpoint 1 then reports for the workload.
 *
 * <p>Mode only on purpose: this endpoint sets the mode and nothing else, so the full window /
 * percentile / headroom editor (a later task) is not represented here.
 */
public record PolicyResponseDto(String mode, String policySource) {
}
