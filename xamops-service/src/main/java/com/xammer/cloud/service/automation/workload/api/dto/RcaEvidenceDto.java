package com.xammer.cloud.service.automation.workload.api.dto;

import java.time.Instant;

/**
 * One durable audit row behind a {@link RcaCauseDto}, carried verbatim so the confidence is
 * auditable.
 *
 * <p>Nullable fields are {@code null} rather than fabricated when the row did not record them.
 *
 * @param at            when the action was applied, or {@code null}
 * @param outcome       the published terminal outcome
 * @param detail        the free-text proof detail, or {@code null}
 * @param correlationId the command correlation id, or {@code null}
 */
public record RcaEvidenceDto(Instant at, String outcome, String detail, String correlationId) {
}
