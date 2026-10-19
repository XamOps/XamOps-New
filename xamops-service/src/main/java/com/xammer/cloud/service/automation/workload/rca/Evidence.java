package com.xammer.cloud.service.automation.workload.rca;

import java.time.Instant;

/**
 * One durable audit row behind a {@link RootCause}, carried verbatim so the confidence number is
 * auditable (the same provenance discipline F1 uses).
 *
 * <p>Every field is copied from a {@code vertical_workload_action} column — nothing is derived or
 * fabricated. {@code at} is the row's {@code appliedAt}, {@code outcome} its published terminal
 * outcome, {@code detail} the free-text proof detail (opaque; see {@link RcaAnalyzer}), and
 * {@code correlationId} the command's idempotency key so a cause joins back to the executor's proof.
 *
 * @param at            when the action was applied, or {@code null} when the row did not record it
 * @param outcome       the published terminal outcome of the row
 * @param detail        the free-text proof detail, or {@code null}
 * @param correlationId the command correlation id, or {@code null}
 */
public record Evidence(Instant at, String outcome, String detail, String correlationId) {
}
