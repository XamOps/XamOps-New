package com.xammer.cloud.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The complete set of labels on one resource or one service, each with its share of the cost.
 *
 * <p>A replacement, not a delta: labels left out are removed. That is what lets a single save
 * change several shares at once and still be checked against the 100% ceiling as a whole.
 *
 * <p>The resource endpoint reads {@link #resource}; the service endpoint reads {@link #service}.
 */
public class LabelSplitRequest {

    /** Provider account id, as everywhere else in the label API. */
    private String accountId;

    /** The resource being labelled, for the resource endpoint. */
    private LabelAssignRequest.ResourceRef resource;

    /** The billing service name exactly as the bill prints it, for the service endpoint. */
    private String service;

    private List<Entry> labels;

    /** Which screen made the change, for the audit trail. Optional. */
    private String source;

    public static class Entry {
        private Long labelId;
        /** 0–100 with at most two decimals. Null means the whole cost. */
        private BigDecimal percent;

        public Long getLabelId() { return labelId; }
        public void setLabelId(Long labelId) { this.labelId = labelId; }

        public BigDecimal getPercent() { return percent; }
        public void setPercent(BigDecimal percent) { this.percent = percent; }
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public LabelAssignRequest.ResourceRef getResource() { return resource; }
    public void setResource(LabelAssignRequest.ResourceRef resource) { this.resource = resource; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public List<Entry> getLabels() { return labels; }
    public void setLabels(List<Entry> labels) { this.labels = labels; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
