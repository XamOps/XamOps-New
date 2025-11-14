package com.xammer.billops.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(uniqueConstraints = {
    // Ensure only one rate per client, region, and usage type
    @UniqueConstraint(columnNames = {"client_id", "regionMatcher", "usageTypeMatcher"})
})
public class CloudFrontPrivateRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Link to the client this rate applies to
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    // The string to match in the bill's region (e.g., "Asia Pacific (Mumbai)", "US East (N. Virginia)")
    @Column(nullable = false)
    private String regionMatcher;

    // The string to match in the bill's usage/resource description
    // e.g., "Bandwidth", "Requests-Tier1", "ZA-Requests-HTTPS-Proxy"
    @Column(nullable = false)
    private String usageTypeMatcher;
    
    // Your private rate
    @Column(precision = 19, scale = 10) // High precision for small rates
    private BigDecimal privateRate;
    
    // The unit you are billing for (e.g., "GB", "Per 10000 Requests")
    private String unit;
    
    // --- GETTERS AND SETTERS (This is the fix) ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public String getRegionMatcher() {
        return regionMatcher;
    }

    public void setRegionMatcher(String regionMatcher) {
        this.regionMatcher = regionMatcher;
    }

    public String getUsageTypeMatcher() {
        return usageTypeMatcher;
    }

    public void setUsageTypeMatcher(String usageTypeMatcher) {
        this.usageTypeMatcher = usageTypeMatcher;
    }

    public BigDecimal getPrivateRate() {
        return privateRate;
    }

    public void setPrivateRate(BigDecimal privateRate) {
        this.privateRate = privateRate;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}