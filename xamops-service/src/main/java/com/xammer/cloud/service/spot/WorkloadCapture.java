package com.xammer.cloud.service.spot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * The workloads on a node at notice (design §B3.3, §B4.2). A capture is either {@code null}
 * (unknown) or this record; an empty {@code entries} list is a real observation of "none".
 *
 * @param entries    normalized workloads, Deployment/StatefulSet first, capped at
 *                   {@link WorkloadCaptureBuilder#MAX_ENTRIES}
 * @param podCount   the true number of qualifying pods, even when {@code entries} was capped
 * @param snapshotAt server time of the snapshot the capture came from
 */
public record WorkloadCapture(List<WorkloadEntry> entries, int podCount, Instant snapshotAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<WorkloadEntry>> LIST = new TypeReference<>() {
    };

    public WorkloadCapture {
        entries = List.copyOf(entries);
    }

    /** One {@code affected_workloads} element: {@code {"namespace","kind","name","podsOnNode"}}. */
    public record WorkloadEntry(String namespace, String kind, String name, int podsOnNode) {
    }

    /** Serializes {@code entries} for {@code affected_workloads}. */
    public static String toJson(List<WorkloadEntry> entries) {
        try {
            return MAPPER.writeValueAsString(entries);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("affected workloads did not serialize", e);
        }
    }

    /**
     * Reads {@code affected_workloads}. Null for a null column (unknown) and for a value that does
     * not parse (also unknown: never an empty list).
     */
    public static List<WorkloadEntry> fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            List<WorkloadEntry> out = MAPPER.readValue(json, LIST);
            return out == null ? null : List.copyOf(out);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }
}
