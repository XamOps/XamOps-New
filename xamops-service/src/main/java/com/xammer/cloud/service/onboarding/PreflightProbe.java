package com.xammer.cloud.service.onboarding;

import java.util.List;

/**
 * The narrow set of AWS lookups the server-side preflight needs (design §6.1), isolated behind an
 * interface so {@link OnboardingPreflightService} can be unit-tested against a stub and so the
 * read-only onboarding build ships inert: with no probe bean wired, every AWS-dependent fact is
 * reported {@code UNKNOWN} and no live call is made. Each method maps to one bounded (10 s) AWS call
 * through the account's assumed CFN role; an implementation signals an access/timeout failure by
 * throwing {@link ProbeUnknownException} (mapped to UNKNOWN) and a hard failure by throwing
 * {@link ProbeFailedException} (mapped to FAIL).
 */
public interface PreflightProbe {

    class ProbeUnknownException extends RuntimeException {
        private final boolean accessDenied;

        public ProbeUnknownException(String message, boolean accessDenied) {
            super(message);
            this.accessDenied = accessDenied;
        }

        public boolean isAccessDenied() {
            return accessDenied;
        }
    }

    class ProbeFailedException extends RuntimeException {
        public ProbeFailedException(String message) {
            super(message);
        }
    }

    record IdentityResult(String mode) {
    } // POD_IDENTITY | IRSA | NONE

    record NodegroupScaling(String name, int min, int desired, int max) {
    }

    /** eks:DescribeCluster reachable? Throws to signal UNKNOWN/FAIL. */
    boolean describeClusterOk(String account, String region, String clusterName);

    /** The cluster's Kubernetes version (e.g. "1.30"). */
    String clusterVersion(String account, String region, String clusterName);

    /** ec2:DescribeInstances + autoscaling:DescribeAutoScalingGroups for this cluster reachable? */
    boolean ec2AndAsgReadOk(String account, String region, String clusterName);

    /** PF-IDENTITY detection (addon/OIDC). */
    IdentityResult detectIdentity(String account, String region, String clusterName);

    /** PF-COMPUTE: true when there is ≥1 managed nodegroup and the cluster is NOT in Auto Mode. */
    boolean hasManagedNodegroupsAndNotAutoMode(String account, String region, String clusterName);

    /** PF-NODEGROUPS via the account role when no fresh agent row exists (max 20 nodegroups). */
    List<NodegroupScaling> describeNodegroups(String account, String region, String clusterName);

    /** PF-HOP-LIMIT (only when identity NONE): every cluster instance has hop limit ≥ 2. */
    boolean allInstancesHaveHopLimitAtLeastTwo(String account, String region, String clusterName);
}
