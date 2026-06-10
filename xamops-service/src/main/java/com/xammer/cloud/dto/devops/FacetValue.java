package com.xammer.cloud.dto.devops;

/**
 * A single facet bucket: a distinct field value and the number of matching
 * scripts that carry it. Used to drive filter UIs (e.g. "aws (12)").
 *
 * Requirements: REQ-2.3.2 (Advanced Filtering)
 */
public class FacetValue {

    private String value;
    private long count;

    public FacetValue() {}

    public FacetValue(String value, long count) {
        this.value = value;
        this.count = count;
    }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }
}
