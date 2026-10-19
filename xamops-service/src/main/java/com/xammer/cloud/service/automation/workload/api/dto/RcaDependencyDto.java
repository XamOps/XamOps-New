package com.xammer.cloud.service.automation.workload.api.dto;

/**
 * A root-cause pattern XamOps cannot yet prove, named honestly with what it needs (design §2.1).
 *
 * <p>Not a fabricated cause: these name the two declared-but-unemitted {@code RcaPatternKind}s
 * ({@code OOM_EVICTION}, {@code HPA_FLAP}) and the data source each depends on, so the UI can tell
 * the operator a cause is <em>possible later</em> rather than silently omitting it.
 *
 * @param kind  the pattern this dependency would enable
 * @param needs the data XamOps must persist before the pattern can be emitted
 */
public record RcaDependencyDto(String kind, String needs) {
}
