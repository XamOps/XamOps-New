package com.xammer.cloud.dto;

import java.util.List;

public class LabelAssignRequest {

    // Provider account ID string (AWS account ID, GCP project ID, etc.)
    // Same value used by every other XamOps API — no internal DB ID needed on the frontend.
    private String accountId;

    // Label to assign / remove
    private Long labelId;

    // Resources to assign / remove the label from
    private List<ResourceRef> resources;

    public static class ResourceRef {
        private String resourceId;
        private String resourceType;
        private String resourceName;
        private String region;

        public String getResourceId() { return resourceId; }
        public void setResourceId(String resourceId) { this.resourceId = resourceId; }

        public String getResourceType() { return resourceType; }
        public void setResourceType(String resourceType) { this.resourceType = resourceType; }

        public String getResourceName() { return resourceName; }
        public void setResourceName(String resourceName) { this.resourceName = resourceName; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public Long getLabelId() { return labelId; }
    public void setLabelId(Long labelId) { this.labelId = labelId; }

    public List<ResourceRef> getResources() { return resources; }
    public void setResources(List<ResourceRef> resources) { this.resources = resources; }
}
