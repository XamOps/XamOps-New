package com.xammer.cloud.service.automation.workload.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The read-only RCA payload for one cluster (design §2.6).
 *
 * <p>Honest empty state travels on the wire: with no history, {@code causes} is empty and
 * {@code analyzed.actionRows} is the real count ({@code 0}), so the UI renders the
 * "not-enough-history" state rather than an empty-looking success. The {@code dependencies} list
 * names the causes XamOps cannot yet prove and what each needs — a fact, not a fabrication.
 *
 * @param generatedAt  when this analysis ran
 * @param windowHours  the trailing window analysed, after the controller's clamp
 * @param analyzed     how much durable history was read
 * @param causes       the ranked root causes, or empty
 * @param dependencies the causes not emitted in v1, each with what it needs
 */
public record RcaResponseDto(Instant generatedAt,
                             int windowHours,
                             AnalyzedDto analyzed,
                             List<RcaCauseDto> causes,
                             List<RcaDependencyDto> dependencies) {

    public RcaResponseDto {
        causes = causes == null ? List.of() : List.copyOf(causes);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /**
     * How much history the analysis read.
     *
     * @param actionRows the number of durable rows analysed (POLICY_CHANGE and out-of-window rows
     *                   excluded), never {@code null}
     * @param workloads  the number of distinct workloads those rows covered
     */
    public record AnalyzedDto(int actionRows, int workloads) {
    }
}
