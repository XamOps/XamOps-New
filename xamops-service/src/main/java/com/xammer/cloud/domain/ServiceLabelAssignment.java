package com.xammer.cloud.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A label on one billing service of one account — a service row of the Charges by service card.
 *
 * <p>The service-level counterpart of {@link ResourceLabelAssignment}. It reaches spend a resource
 * label cannot: charges with no resource behind them, such as a Marketplace subscription, and a
 * service the reader wants to attribute as a whole without labelling each resource in it.
 */
@Entity
@Table(name = "service_label_assignments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"cloud_account_id", "service_name", "label_id"}))
public class ServiceLabelAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloud_account_id", nullable = false)
    private CloudAccount cloudAccount;

    /** The product name exactly as the bill prints it, which is what the card groups on. */
    @Column(name = "service_name", nullable = false, length = 255)
    private String serviceName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "label_id", nullable = false)
    private ResourceLabel label;

    /** Share of the service's cost, 0–100 with two decimals. Null means the whole service. */
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

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public ResourceLabel getLabel() { return label; }
    public void setLabel(ResourceLabel label) { this.label = label; }

    public BigDecimal getSplitPercent() { return splitPercent; }
    public void setSplitPercent(BigDecimal splitPercent) { this.splitPercent = splitPercent; }

    public String getTaggedBy() { return taggedBy; }
    public void setTaggedBy(String taggedBy) { this.taggedBy = taggedBy; }

    public LocalDateTime getTaggedAt() { return taggedAt; }
    public void setTaggedAt(LocalDateTime taggedAt) { this.taggedAt = taggedAt; }
}
