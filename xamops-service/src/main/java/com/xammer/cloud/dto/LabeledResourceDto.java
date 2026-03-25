package com.xammer.cloud.dto;

import com.xammer.cloud.domain.ResourceLabelAssignment;

import java.time.LocalDateTime;

/** One resource row as seen from the "View by label" page */
public class LabeledResourceDto {

    private String resourceId;
    private String resourceType;
    private String resourceName;
    private String region;
    private String taggedBy;
    private LocalDateTime taggedAt;

    public static LabeledResourceDto from(ResourceLabelAssignment a) {
        LabeledResourceDto dto = new LabeledResourceDto();
        dto.resourceId   = a.getResourceId();
        dto.resourceType = a.getResourceType();
        dto.resourceName = a.getResourceName();
        dto.region       = a.getRegion();
        dto.taggedBy     = a.getTaggedBy();
        dto.taggedAt     = a.getTaggedAt();
        return dto;
    }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getTaggedBy() { return taggedBy; }
    public void setTaggedBy(String taggedBy) { this.taggedBy = taggedBy; }

    public LocalDateTime getTaggedAt() { return taggedAt; }
    public void setTaggedAt(LocalDateTime taggedAt) { this.taggedAt = taggedAt; }
}
