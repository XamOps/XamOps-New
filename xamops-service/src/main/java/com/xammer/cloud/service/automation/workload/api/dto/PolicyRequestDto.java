package com.xammer.cloud.service.automation.workload.api.dto;

/**
 * Endpoint 2's request body. The published contract carries the full policy; this release reads only
 * {@code mode}:
 *
 * <pre>{@code { "mode": "AUTO" } }</pre>
 *
 * <p>{@code mode} is validated in {@link com.xammer.cloud.service.automation.workload.api.VerticalApplyService},
 * which answers {@code 400 K8S_INVALID_POLICY} naming the {@code mode} field for anything that is not
 * {@code OFF}, {@code RECOMMEND} or {@code AUTO} (null and blank included). No validation lives here,
 * so an absent body reaches the service as a null mode and is refused there — never defaulted to a
 * value nobody asked for.
 *
 * <p>Any other policy field (window, percentile, headroom) is a later task and is deliberately not
 * part of this record yet, so a client cannot believe it was saved.
 */
public record PolicyRequestDto(String mode) {
}
