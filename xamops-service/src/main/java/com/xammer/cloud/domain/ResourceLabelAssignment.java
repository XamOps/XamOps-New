package com.xammer.cloud.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "resource_label_assignments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"cloud_account_id", "resource_id", "label_id"}))
public class ResourceLabelAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloud_account_id", nullable = false)
    private CloudAccount cloudAccount;

    /**
     * The resource this label is on, normalised by {@code ResourceIdResolver} to the
     * inventory's short id wherever the inventory knows the resource.
     *
     * <p>512 to match {@code aws_cloud_resource.resource_id}, because an unresolvable id is
     * stored as received and the Cost and Usage Report supplies full ARNs for several
     * services.
     */
    @Column(name = "resource_id", nullable = false, length = 512)
    private String resourceId;

    @Column(name = "resource_type", nullable = false, length = 100)
    private String resourceType;

    @Column(name = "resource_name", length = 255)
    private String resourceName;

    @Column(name = "region", length = 50)
    private String region;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "label_id", nullable = false)
    private ResourceLabel label;

    /**
     * The share of the resource's cost this label takes, 0–100 with two decimals.
     *
     * <p>Null means the whole resource — what every row written before percentages existed
     * means, and what the plain assign endpoint still writes. The shares on one resource add up
     * to at most 100; the rest is unallocated.
     */
    @Column(name = "split_percent", precision = 5, scale = 2)
    private BigDecimal splitPercent;

    @Column(name = "tagged_by", length = 100)
    private String taggedBy;

    @Column(name = "tagged_at", nullable = false)
    private LocalDateTime taggedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public CloudAccount getCloudAccount() { return cloudAccount; }
    public void setCloudAccount(CloudAccount cloudAccount) { this.cloudAccount = cloudAccount; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public ResourceLabel getLabel() { return label; }
    public void setLabel(ResourceLabel label) { this.label = label; }

    public BigDecimal getSplitPercent() { return splitPercent; }
    public void setSplitPercent(BigDecimal splitPercent) { this.splitPercent = splitPercent; }

    public String getTaggedBy() { return taggedBy; }
    public void setTaggedBy(String taggedBy) { this.taggedBy = taggedBy; }

    public LocalDateTime getTaggedAt() { return taggedAt; }
    public void setTaggedAt(LocalDateTime taggedAt) { this.taggedAt = taggedAt; }
}
