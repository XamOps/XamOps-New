package com.xammer.cloud.dto.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Generic wrapper for Authentik paginated list responses.
 *
 * All Authentik list endpoints return:
 * {
 *   "pagination": { "next": 2, "previous": 0, "count": 150, "current": 1,
 *                   "total_pages": 3, "start_index": 1, "end_index": 50 },
 *   "results": [ ... ]
 * }
 *
 * AuthentikApiClient auto-paginates through all pages using the `next` pointer.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthentikPagedResponse<T> {

    @JsonProperty("pagination")
    private Pagination pagination;

    @JsonProperty("results")
    private List<T> results;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pagination {

        /** Page number of the next page; 0 means no more pages. */
        @JsonProperty("next")
        private int next;

        @JsonProperty("previous")
        private int previous;

        @JsonProperty("count")
        private int count;

        @JsonProperty("current")
        private int current;

        @JsonProperty("total_pages")
        private int totalPages;
    }
}
