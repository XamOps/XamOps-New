package com.xammer.cloud.permissions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One cloud API call that XamOps is not allowed to make for an account, and the
 * consequence of that in product terms.
 *
 * Identity is {@link #action} — one entry per IAM action per account, with regions
 * and occurrence count folded in. A dashboard refresh can produce thousands of
 * identical denials a minute; collapsing them here is what keeps the banner
 * readable and the store small.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PermissionGap {

    /** Why the data is missing. Drives the wording the customer sees. */
    public enum Kind {
        /** The IAM role is missing the action (policy edited, role replaced). */
        PERMISSION,
        /** The action exists but the AWS service itself is not enabled/opted in. */
        SERVICE_NOT_ENABLED,
        /** XamOps cannot assume the cross-account role at all — nothing works. */
        ROLE_UNREACHABLE
    }

    private String accountId;
    private String provider = "AWS";
    private Kind kind = Kind.PERMISSION;
    private String service;
    private String serviceLabel;
    private String operation;
    private String action;
    private Set<String> regions = new LinkedHashSet<>();
    private String errorCode;
    private String message;
    private List<String> features = List.of();
    private List<String> featureLabels = List.of();
    private Instant firstSeen;
    private Instant lastSeen;
    private long occurrences;

    public PermissionGap() {
    }

    /** Merges a newer sighting of the same action into this entry. */
    public void mergeSighting(PermissionGap sighting) {
        this.regions.addAll(sighting.getRegions());
        this.lastSeen = sighting.getLastSeen();
        this.occurrences += Math.max(1, sighting.getOccurrences());
        if (sighting.getMessage() != null) {
            this.message = sighting.getMessage();
        }
        if (sighting.getErrorCode() != null) {
            this.errorCode = sighting.getErrorCode();
        }
        // A role that has become unreachable outranks a plain missing action.
        if (sighting.getKind() == Kind.ROLE_UNREACHABLE) {
            this.kind = Kind.ROLE_UNREACHABLE;
        }
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getServiceLabel() {
        return serviceLabel;
    }

    public void setServiceLabel(String serviceLabel) {
        this.serviceLabel = serviceLabel;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Set<String> getRegions() {
        return regions;
    }

    public void setRegions(Set<String> regions) {
        this.regions = regions != null ? regions : new LinkedHashSet<>();
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getFeatures() {
        return features;
    }

    public void setFeatures(List<String> features) {
        this.features = features != null ? features : List.of();
    }

    public List<String> getFeatureLabels() {
        return featureLabels;
    }

    public void setFeatureLabels(List<String> featureLabels) {
        this.featureLabels = featureLabels != null ? featureLabels : List.of();
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public long getOccurrences() {
        return occurrences;
    }

    public void setOccurrences(long occurrences) {
        this.occurrences = occurrences;
    }
}
