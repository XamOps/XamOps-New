package com.xammer.cloud.service.automation.workload.rca;

import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;

import java.util.List;

/**
 * One ranked, explainable root cause produced by {@link RcaAnalyzer} over durable audit history.
 *
 * <p>A pure value: it carries the measured facts (the {@code occurrences} count, the
 * {@code windowHours} it was found in, the {@link Evidence} rows) and the derived
 * {@code confidence}/{@code remediation}, with no I/O and no persistence. The number is auditable
 * because its evidence travels with it.
 *
 * @param kind        the pattern this cause names
 * @param title       a short human title for the kind
 * @param confidence  the {@code [0,1]} evidence-strength score (design §2.3)
 * @param workload    the workload the cause is about
 * @param remediation the concrete, data-filled remediation sentence (design §2.4)
 * @param occurrences the structured occurrence count the confidence was computed from
 * @param windowHours the trailing window the cause was found in
 * @param evidence    representative durable rows behind the cause
 */
public record RootCause(RcaPatternKind kind,
                        String title,
                        double confidence,
                        VerticalWorkloadDto.WorkloadRefDto workload,
                        String remediation,
                        int occurrences,
                        int windowHours,
                        List<Evidence> evidence) {

    public RootCause {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
