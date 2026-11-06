package com.xammer.cloud.dto;

import com.xammer.cloud.domain.ResourceLabel;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ResourceLabelDto {

    private Long id;
    private String name;
    private String color;
    private String description;
    private LocalDateTime createdAt;
    private String createdBy;
    private long resourceCount; // populated in summary responses
    /**
     * The share of the resource's or service's cost this label takes, when the DTO describes an
     * assignment rather than the label itself. Null means the whole cost, and is always null on
     * the plain label list.
     */
    private BigDecimal percent;

    public static ResourceLabelDto from(ResourceLabel label) {
        ResourceLabelDto dto = new ResourceLabelDto();
        dto.id = label.getId();
        dto.name = label.getName();
        dto.color = label.getColor();
        dto.description = label.getDescription();
        dto.createdAt = label.getCreatedAt();
        dto.createdBy = label.getCreatedBy();
        return dto;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public long getResourceCount() { return resourceCount; }
    public void setResourceCount(long resourceCount) { this.resourceCount = resourceCount; }

    public BigDecimal getPercent() { return percent; }
    public void setPercent(BigDecimal percent) { this.percent = percent; }

    /** The label as assigned, carrying the assignment's share. */
    public static ResourceLabelDto from(ResourceLabel label, BigDecimal percent) {
        ResourceLabelDto dto = from(label);
        dto.percent = percent;
        return dto;
    }
}
