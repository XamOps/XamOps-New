package com.xammer.cloud.service.automation.workload.api.dto;

import java.util.List;

/**
 * One ranked root cause, shaped for the UI (design §2.6).
 *
 * <p>Every field is a measured or derived fact carried from {@code RootCause}; nullable fields are
 * {@code null} rather than {@code 0} when absent.
 *
 * @param kind        the pattern name (a {@code RcaPatternKind})
 * @param title       a short human title
 * @param confidence  the {@code [0,1]} evidence-strength score
 * @param workload    the workload the cause is about
 * @param occurrences the structured occurrence count the confidence was computed from
 * @param windowHours the trailing window the cause was found in
 * @param remediation the concrete remediation sentence
 * @param evidence    representative durable rows behind the cause
 */
public record RcaCauseDto(String kind,
                          String title,
                          double confidence,
                          VerticalWorkloadDto.WorkloadRefDto workload,
                          int occurrences,
                          int windowHours,
                          String remediation,
                          List<RcaEvidenceDto> evidence) {

    public RcaCauseDto {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
