package com.xammer.cloud.dto.devops;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic, paginated container for search results. Holds the matched items
 * (in relevance/sort order) together with pagination metadata.
 *
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.3.4 (Pagination)
 *
 * @param <T> the type of item returned (e.g. {@code DevOpsScript})
 */
public class SearchResult<T> {

    private List<T> items = new ArrayList<>();
    private long totalHits;
    private int page;
    private int size;
    private int totalPages;
    private long tookMillis;

    public SearchResult() {}

    public SearchResult(List<T> items, long totalHits, int page, int size, long tookMillis) {
        this.items = items != null ? items : new ArrayList<>();
        this.totalHits = totalHits;
        this.page = page;
        this.size = size;
        this.tookMillis = tookMillis;
        this.totalPages = size > 0 ? (int) Math.ceil((double) totalHits / size) : 0;
    }

    /** Convenience factory for an empty result set. */
    public static <T> SearchResult<T> empty(int page, int size) {
        return new SearchResult<>(new ArrayList<>(), 0, page, size, 0);
    }

    public List<T> getItems() { return items; }
    public void setItems(List<T> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    public long getTotalHits() { return totalHits; }
    public void setTotalHits(long totalHits) {
        this.totalHits = totalHits;
        this.totalPages = size > 0 ? (int) Math.ceil((double) totalHits / size) : 0;
    }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) {
        this.size = size;
        this.totalPages = size > 0 ? (int) Math.ceil((double) totalHits / size) : 0;
    }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public long getTookMillis() { return tookMillis; }
    public void setTookMillis(long tookMillis) { this.tookMillis = tookMillis; }

    /** @return true when there are more pages after the current one. */
    public boolean hasMore() {
        return (page + 1) < totalPages;
    }
}
