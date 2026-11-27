package com.xammer.cloud.dto.billops;

/** One cloud account on a {@link BillOpsClientDetailsDto} row. */
public class BillOpsAccountDto {
    private Long id;
    private String name;
    private String providerAccountId;
    private String provider;
    private Double consumption;
    private Double creditsApplied;
    /**
     * CONNECTED / PENDING / NOT_CONNECTED, or null where onboarding does not apply.
     *
     * Null rather than NOT_CONNECTED for non-AWS accounts: self-service onboarding is AWS-only, so
     * reporting a GCP project as "not onboarded" would read as a gap the admin team could close.
     */
    private String upwindStatus;
    /** Workloads under management (compute assets), the unit a licence is most likely counted in. */
    private long upwindWorkloads;
    /** Of those, how many a runtime sensor actually covers. */
    private long upwindSensorWorkloads;

    // Getters and Setters
    public String getUpwindStatus() {
        return upwindStatus;
    }

    public void setUpwindStatus(String upwindStatus) {
        this.upwindStatus = upwindStatus;
    }

    public long getUpwindWorkloads() {
        return upwindWorkloads;
    }

    public void setUpwindWorkloads(long upwindWorkloads) {
        this.upwindWorkloads = upwindWorkloads;
    }

    public long getUpwindSensorWorkloads() {
        return upwindSensorWorkloads;
    }

    public void setUpwindSensorWorkloads(long upwindSensorWorkloads) {
        this.upwindSensorWorkloads = upwindSensorWorkloads;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProviderAccountId() {
        return providerAccountId;
    }

    public void setProviderAccountId(String providerAccountId) {
        this.providerAccountId = providerAccountId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Double getConsumption() {
        return consumption;
    }

    public void setConsumption(Double consumption) {
        this.consumption = consumption;
    }

    public Double getCreditsApplied() {
        return creditsApplied;
    }

    public void setCreditsApplied(Double creditsApplied) {
        this.creditsApplied = creditsApplied;
    }
}
