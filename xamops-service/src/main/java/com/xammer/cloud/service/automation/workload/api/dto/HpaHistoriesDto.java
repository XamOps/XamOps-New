package com.xammer.cloud.service.automation.workload.api.dto;

import java.util.List;

/**
 * The response body of {@code GET /api/eks/automation/hpa/{cluster}/history}: several HPAs'
 * histories for the HPA list, read with one cluster connection and one query per series rather than
 * one connection and seven queries per HPA.
 *
 * <p>Each entry of {@code histories} is exactly what
 * {@code GET .../hpa/{cluster}/{namespace}/{name}/history} answers for that HPA, its
 * {@code unavailable} included: a source failure is one {@code 200} whose every entry carries the same
 * {@code unavailable}, with nothing invented.
 *
 * @param schemaVersion 1
 * @param window        {@code 24h | 72h | 168h}
 * @param histories     one per HPA asked for that Prometheus or the cluster knows, in the order asked
 * @param missing       the HPAs neither Prometheus nor the cluster knows, which the one-HPA read answers
 *                      with a {@code 404}
 */
public record HpaHistoriesDto(int schemaVersion, String window, List<HpaHistoryDto> histories,
                              List<HpaRef> missing) {

    public HpaHistoriesDto {
        histories = histories == null ? List.of() : List.copyOf(histories);
        missing = missing == null ? List.of() : List.copyOf(missing);
    }

    /** One HPA, by namespace and name. */
    public record HpaRef(String namespace, String name) {
    }
}
