package com.xammer.cloud.dto.devops;

/**
 * Options that tune a script search request: the filters to apply plus
 * pagination parameters.
 *
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.3.4 (Pagination)
 */
public class SearchOptions {

    /** Filters to apply alongside the full-text query. */
    private ScriptFilters filters = new ScriptFilters();

    /** Zero-based page number. */
    private int page = 0;

    /** Number of results per page. */
    private int pageSize = 20;

    public SearchOptions() {}

    public SearchOptions(ScriptFilters filters, int page, int pageSize) {
        this.filters = filters != null ? filters : new ScriptFilters();
        this.page = page;
        this.pageSize = pageSize;
    }

    public ScriptFilters getFilters() { return filters; }
    public void setFilters(ScriptFilters filters) {
        this.filters = filters != null ? filters : new ScriptFilters();
    }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
