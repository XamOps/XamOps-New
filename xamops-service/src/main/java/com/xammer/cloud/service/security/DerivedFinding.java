package com.xammer.cloud.service.security;

/**
 * A finding XamOps worked out for itself, rather than one AWS reported.
 *
 * <p>
 * Produced by two kinds of detector. {@link SecurityFindingDetector} recognises events that are
 * dangerous by name alone — deleting a CloudTrail trail is bad regardless of the arguments.
 * {@link ExposureDetector} has to read the request parameters, because the same event name can be
 * routine or critical depending on one field: {@code AuthorizeSecurityGroupIngress} opening port
 * 443 to the office VPN and opening port 22 to {@code 0.0.0.0/0} are the same API call.
 *
 * <p>
 * {@link #key} exists so repeat occurrences collapse. It is combined with the account to build the
 * stored finding id, which means a second removal of Block Public Access on the same bucket
 * updates the finding already open instead of stacking a new row — and a change to a *different*
 * bucket is correctly a separate finding.
 */
public final class DerivedFinding {

    /** Distinguishes one occurrence from another; usually event name plus resource. */
    public final String key;
    public final String title;
    public final String severity;
    public final String description;
    public final String remediation;
    public final String resourceType;

    /**
     * Resource identifier read out of the event detail, or null.
     *
     * <p>
     * Often better than the ARN the forwarder derives, because these events name the thing they
     * changed: a security group event carries {@code groupId} even though no ARN appears anywhere
     * in the payload.
     */
    public final String resourceId;

    public DerivedFinding(String key, String title, String severity, String resourceType,
            String resourceId, String description, String remediation) {
        this.key = key;
        this.title = title;
        this.severity = severity;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.description = description;
        this.remediation = remediation;
    }
}
