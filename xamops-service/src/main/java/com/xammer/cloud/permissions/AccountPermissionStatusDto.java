package com.xammer.cloud.permissions;

import java.util.List;

/**
 * What the UI needs to explain a hole in the data for one account: the gaps
 * themselves, plus a ready-to-show sentence so every page does not have to
 * invent its own wording.
 */
public class AccountPermissionStatusDto {

    /** Overall state of the account's access, worst-first. */
    public enum Health {
        /** Everything XamOps asked for succeeded. */
        HEALTHY,
        /** Some data is missing, the rest works. */
        DEGRADED,
        /** The cross-account role cannot be assumed — no data at all. */
        UNREACHABLE
    }

    private String accountId;
    private String accountName;
    private String provider;
    private String accessType;
    private Health health = Health.HEALTHY;
    private String summary;
    private List<PermissionGap> gaps = List.of();
    private List<String> missingActions = List.of();
    private List<String> affectedFeatures = List.of();

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getAccessType() {
        return accessType;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
    }

    public Health getHealth() {
        return health;
    }

    public void setHealth(Health health) {
        this.health = health;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<PermissionGap> getGaps() {
        return gaps;
    }

    public void setGaps(List<PermissionGap> gaps) {
        this.gaps = gaps != null ? gaps : List.of();
    }

    public List<String> getMissingActions() {
        return missingActions;
    }

    public void setMissingActions(List<String> missingActions) {
        this.missingActions = missingActions != null ? missingActions : List.of();
    }

    public List<String> getAffectedFeatures() {
        return affectedFeatures;
    }

    public void setAffectedFeatures(List<String> affectedFeatures) {
        this.affectedFeatures = affectedFeatures != null ? affectedFeatures : List.of();
    }
}
