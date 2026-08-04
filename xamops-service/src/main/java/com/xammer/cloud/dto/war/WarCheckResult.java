package com.xammer.cloud.dto.war;

import java.util.ArrayList;
import java.util.List;

/**
 * What an automated check found for one control.
 *
 * <p>
 * {@code evidence} is the point of this class. "MFA is not enforced" is an
 * opinion; "these 4 IAM users have console passwords and no MFA device" is
 * something an engineer can act on before lunch.
 */
public class WarCheckResult {

    private WarStatus status;
    private final List<String> evidence = new ArrayList<>();
    private String summary;
    private String region;
    private int affectedCount;
    private int scannedCount;
    private String deniedAction;

    public WarCheckResult(WarStatus status) {
        this.status = status;
    }

    public static WarCheckResult pass(String summary) {
        WarCheckResult r = new WarCheckResult(WarStatus.PASS);
        r.summary = summary;
        return r;
    }

    public static WarCheckResult fail(String summary) {
        WarCheckResult r = new WarCheckResult(WarStatus.FAIL);
        r.summary = summary;
        return r;
    }

    public static WarCheckResult warn(String summary) {
        WarCheckResult r = new WarCheckResult(WarStatus.WARN);
        r.summary = summary;
        return r;
    }

    public static WarCheckResult notApplicable(String summary) {
        WarCheckResult r = new WarCheckResult(WarStatus.NOT_APPLICABLE);
        r.summary = summary;
        return r;
    }

    /**
     * The check could not complete. Carries the IAM action that was denied when
     * known, so the UI can tell the customer which permission to grant instead of
     * showing an unexplained blank.
     */
    public static WarCheckResult unknown(String summary, String deniedAction) {
        WarCheckResult r = new WarCheckResult(WarStatus.UNKNOWN);
        r.summary = summary;
        r.deniedAction = deniedAction;
        return r;
    }

    /** FAIL when any evidence was collected, PASS otherwise. */
    public static WarCheckResult of(List<String> evidence, String failSummary, String passSummary) {
        WarCheckResult r = new WarCheckResult(evidence.isEmpty() ? WarStatus.PASS : WarStatus.FAIL);
        r.evidence.addAll(evidence);
        r.affectedCount = evidence.size();
        r.summary = evidence.isEmpty() ? passSummary : failSummary;
        return r;
    }

    public WarCheckResult withEvidence(List<String> items) {
        this.evidence.addAll(items);
        this.affectedCount = this.evidence.size();
        return this;
    }

    public WarCheckResult withEvidence(String item) {
        this.evidence.add(item);
        this.affectedCount = this.evidence.size();
        return this;
    }

    public WarCheckResult withScanned(int scannedCount) {
        this.scannedCount = scannedCount;
        return this;
    }

    public WarCheckResult withRegion(String region) {
        this.region = region;
        return this;
    }

    public WarStatus getStatus() {
        return status;
    }

    public void setStatus(WarStatus status) {
        this.status = status;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getRegion() {
        return region;
    }

    public int getAffectedCount() {
        return affectedCount;
    }

    public void setAffectedCount(int affectedCount) {
        this.affectedCount = affectedCount;
    }

    public int getScannedCount() {
        return scannedCount;
    }

    public String getDeniedAction() {
        return deniedAction;
    }
}
