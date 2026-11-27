package com.xammer.cloud.dto.billops;

import java.util.List;

/**
 * One client row of the BillOps admin Accounts list ({@code GET /api/xamops/billops-admin/clients}).
 *
 * <p>Built only by {@code BillOpsClientListService}, and cached in Redis as JSON. Jackson writes
 * through the getters and reads back through the setters, so a field missing either one is
 * silently dropped on its way through the cache.
 */
public class BillOpsClientDetailsDto {
    private Long id;
    private String name;
    private String email;
    private List<BillOpsAccountDto> accounts;
    private List<BillOpsUserDto> users;
    private Double totalConsumption;
    private boolean starred;
    /** Whether this client may self-serve XamSecure onboarding. Defaults on. */
    private boolean xamSecureOnboardingEnabled = true;
    /**
     * Per-client entitlement for the chargeable notification channels. Both default OFF, unlike
     * xamSecureOnboardingEnabled — every send is billed per message, so a client absent from a
     * stale cache must read as not entitled rather than as entitled.
     */
    private boolean smsNotificationsEnabled = false;
    private boolean whatsappNotificationsEnabled = false;

    // ── Upwind / XamSecure estate, for the admin team ────────────────────────────────────
    /** Accounts reporting CONNECTED. A client is "onboarded" when this is > 0. */
    private int upwindConnectedAccounts;
    /** Accounts handed a launch URL but not yet confirmed. */
    private int upwindPendingAccounts;
    /**
     * Accounts that COULD be onboarded — AWS only, since self-service is AWS-only.
     *
     * Reported alongside the connected count so "1 of 4" is distinguishable from "1 of 1": the
     * first is an upsell, the second is done.
     */
    private int upwindEligibleAccounts;
    /** Workloads under management across the client's connected accounts. */
    private long upwindWorkloads;
    /**
     * Sensor-covered workloads — our best available proxy for licence consumption.
     *
     * Upwind exposes no usage, entitlement or licence endpoint (probed 2026-09-07: usage,
     * entitlements, licenses, subscription, billing, quota all 404 on v1 and v2), so consumption
     * has to be metered locally. This counts workloads whose catalog entry reports a runtime
     * sensor. Treat it as an indicator to reconcile against Upwind's invoice, not as a bill:
     * their billable unit is still unconfirmed, and workload-vs-node alone differs by ~7x on a
     * Kubernetes-heavy estate.
     */
    private long upwindSensorWorkloads;

    // Getters and Setters
    public int getUpwindConnectedAccounts() {
        return upwindConnectedAccounts;
    }

    public void setUpwindConnectedAccounts(int upwindConnectedAccounts) {
        this.upwindConnectedAccounts = upwindConnectedAccounts;
    }

    public int getUpwindPendingAccounts() {
        return upwindPendingAccounts;
    }

    public void setUpwindPendingAccounts(int upwindPendingAccounts) {
        this.upwindPendingAccounts = upwindPendingAccounts;
    }

    public int getUpwindEligibleAccounts() {
        return upwindEligibleAccounts;
    }

    public void setUpwindEligibleAccounts(int upwindEligibleAccounts) {
        this.upwindEligibleAccounts = upwindEligibleAccounts;
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<BillOpsAccountDto> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<BillOpsAccountDto> accounts) {
        this.accounts = accounts;
    }

    public List<BillOpsUserDto> getUsers() {
        return users;
    }

    public void setUsers(List<BillOpsUserDto> users) {
        this.users = users;
    }

    public Double getTotalConsumption() {
        return totalConsumption;
    }

    public void setTotalConsumption(Double totalConsumption) {
        this.totalConsumption = totalConsumption;
    }

    public boolean isStarred() {
        return starred;
    }

    public void setStarred(boolean starred) {
        this.starred = starred;
    }

    public boolean isXamSecureOnboardingEnabled() {
        return xamSecureOnboardingEnabled;
    }

    public void setXamSecureOnboardingEnabled(boolean xamSecureOnboardingEnabled) {
        this.xamSecureOnboardingEnabled = xamSecureOnboardingEnabled;
    }

    public boolean isSmsNotificationsEnabled() {
        return smsNotificationsEnabled;
    }

    public void setSmsNotificationsEnabled(boolean smsNotificationsEnabled) {
        this.smsNotificationsEnabled = smsNotificationsEnabled;
    }

    public boolean isWhatsappNotificationsEnabled() {
        return whatsappNotificationsEnabled;
    }

    public void setWhatsappNotificationsEnabled(boolean whatsappNotificationsEnabled) {
        this.whatsappNotificationsEnabled = whatsappNotificationsEnabled;
    }
}
