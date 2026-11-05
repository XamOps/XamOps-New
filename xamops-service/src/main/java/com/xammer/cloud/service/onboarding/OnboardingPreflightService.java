package com.xammer.cloud.service.onboarding;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.onboarding.PreflightCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side preflight (design §6.1), run through the account's assumed CFN role. Results are
 * cached per (cluster or config, phase) for 60 s. Each AWS fact comes from {@link PreflightProbe};
 * an {@code AccessDenied}/timeout maps to {@code UNKNOWN}, a hard error to {@code FAIL}, and
 * {@code UNKNOWN} on a blocking check counts as blocking (fail closed).
 *
 * <p>Ships inert: with no {@link PreflightProbe} bean wired, every AWS-dependent check is
 * {@code UNKNOWN} and no live call is made (honours "NO live cluster mutation this run").</p>
 */
@Service
public class OnboardingPreflightService {

    private static final Logger logger = LoggerFactory.getLogger(OnboardingPreflightService.class);

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final List<String> BOTH = List.of("READ_ONLY", "AUTOMATION");
    private static final List<String> AUTOMATION_ONLY = List.of("AUTOMATION");

    private final PreflightProbe probe; // null ⇒ inert
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public OnboardingPreflightService(Optional<PreflightProbe> probe, Clock clock) {
        this.probe = probe.orElse(null);
        this.clock = clock;
    }

    public record Result(Instant ranAt, List<PreflightCheck> checks, boolean blocking,
                         String identityMode, String nodeScaler) {
    }

    private record Cached(Instant at, Result result) {
    }

    /** Run (or return cached) preflight for one cluster/phase. */
    public Result run(CloudAccount account, KubernetesClusterConfig config, String clusterName,
                      String region, String phase) {
        String key = (config != null ? "cfg:" + config.getId() : "cluster:" + clusterName) + "|" + phase;
        Cached cached = cache.get(key);
        Instant now = Instant.now(clock);
        if (cached != null && cached.at().isAfter(now.minus(CACHE_TTL))) {
            return cached.result();
        }
        Result result = compute(account, config, clusterName, region, phase, now);
        cache.put(key, new Cached(now, result));
        return result;
    }

    private Result compute(CloudAccount account, KubernetesClusterConfig config, String clusterName,
                           String region, String phase, Instant now) {
        boolean automation = "AUTOMATION".equals(phase);
        String awsAccount = account == null ? null : account.getAwsAccountId();
        List<PreflightCheck> checks = new ArrayList<>();

        // PF-ACCOUNT — from stored connection status; no AWS call.
        boolean accountConnected = account != null && "CONNECTED".equalsIgnoreCase(account.getStatus());
        checks.add(new PreflightCheck("PF-ACCOUNT",
                accountConnected ? PreflightCheck.PASS : PreflightCheck.FAIL, BOTH,
                "AWS account connected", accountConnected ? "Account is CONNECTED" : "Account is not connected",
                accountConnected ? null : new PreflightCheck.Remediation(
                        "Reconnect the AWS account in Settings > Cloud accounts", null)));

        // PF-EKS-READ
        checks.add(boolProbe("PF-EKS-READ", BOTH, "Read the cluster",
                () -> probe.describeClusterOk(awsAccount, region, clusterName),
                "XamOps' account role can't read this cluster. Update the XamOps read-only stack."));

        // PF-KUBE-VERSION
        checks.add(kubeVersionCheck(awsAccount, region, clusterName));

        // PF-IDENTITY (also yields the detected identity mode)
        String identityMode = null;
        PreflightCheck identity = identityCheck(awsAccount, region, clusterName, config, automation);
        identityMode = switch (identity.result()) {
            case PreflightCheck.PASS, PreflightCheck.WARN -> identity.detail(); // detail carries the mode
            default -> null;
        };
        checks.add(identity);

        // PF-HOP-LIMIT only when identity is NONE.
        if ("NONE".equals(identityMode)) {
            checks.add(boolProbeNeverBlocks("PF-HOP-LIMIT", "Instance metadata hop limit",
                    () -> probe.allInstancesHaveHopLimitAtLeastTwo(awsAccount, region, clusterName),
                    "Set hop limit 2 on the nodegroup launch template, or prefer Pod Identity."));
        }

        // PF-COMPUTE (blocks the NODE_CONSOLIDATION feature only — surfaced as INFO/PASS here)
        checks.add(computeCheck(awsAccount, region, clusterName));

        boolean blocking = checks.stream().anyMatch(c -> isBlocking(c, phase));
        String nodeScaler = config == null ? null : config.getNodeScaler();
        return new Result(now, checks, blocking, identityMode, nodeScaler);
    }

    private PreflightCheck kubeVersionCheck(String account, String region, String clusterName) {
        if (probe == null) {
            return unknown("PF-KUBE-VERSION", BOTH, "Kubernetes version");
        }
        try {
            String version = probe.clusterVersion(account, region, clusterName);
            double v = parseVersion(version);
            if (v >= 1.28) {
                return new PreflightCheck("PF-KUBE-VERSION", PreflightCheck.PASS, BOTH,
                        "Kubernetes version", "Cluster is on " + version, null);
            }
            if (v >= 1.24) {
                return new PreflightCheck("PF-KUBE-VERSION", PreflightCheck.WARN, BOTH,
                        "Kubernetes version", "Cluster is on " + version + " (upgrade recommended)", null);
            }
            return new PreflightCheck("PF-KUBE-VERSION", PreflightCheck.FAIL, BOTH,
                    "Kubernetes version", "Cluster is on " + version,
                    new PreflightCheck.Remediation("Upgrade the cluster", "aws eks update-cluster-version ..."));
        } catch (PreflightProbe.ProbeUnknownException e) {
            return unknown("PF-KUBE-VERSION", BOTH, "Kubernetes version");
        } catch (RuntimeException e) {
            logger.warn("PF-KUBE-VERSION failed: {}", e.getClass().getSimpleName());
            return unknown("PF-KUBE-VERSION", BOTH, "Kubernetes version");
        }
    }

    private PreflightCheck identityCheck(String account, String region, String clusterName,
                                         KubernetesClusterConfig config, boolean automation) {
        if (probe == null) {
            return unknown("PF-IDENTITY", automation ? BOTH : List.of("AUTOMATION"), "Pod-scoped AWS identity");
        }
        try {
            String mode = probe.detectIdentity(account, region, clusterName).mode();
            // Phase 2 with an existing stack: the stored mode must still be available (§4.3a).
            if (automation && config != null && config.getReadRoleArn() != null
                    && config.getIdentityMode() != null && !config.getIdentityMode().equals(mode)) {
                return new PreflightCheck("PF-IDENTITY", PreflightCheck.FAIL, AUTOMATION_ONLY,
                        "Pod-scoped AWS identity",
                        "This cluster's XamOps stack uses " + config.getIdentityMode()
                                + ", but that mode isn't active now",
                        new PreflightCheck.Remediation("Restore the stored identity mode", null));
            }
            // In phase 1, identity issues WARN (don't block a read-only connect); in phase 2 they block.
            List<String> blocks = automation ? AUTOMATION_ONLY : List.of();
            String result = "NONE".equals(mode)
                    ? (automation ? PreflightCheck.FAIL : PreflightCheck.WARN)
                    : PreflightCheck.PASS;
            return new PreflightCheck("PF-IDENTITY", result, blocks, "Pod-scoped AWS identity",
                    mode, "NONE".equals(mode)
                    ? new PreflightCheck.Remediation("Install the Pod Identity agent",
                        "aws eks create-addon --cluster-name " + clusterName
                                + " --addon-name eks-pod-identity-agent --region " + region)
                    : null);
        } catch (PreflightProbe.ProbeUnknownException e) {
            return unknown("PF-IDENTITY", automation ? AUTOMATION_ONLY : List.of(), "Pod-scoped AWS identity");
        } catch (RuntimeException e) {
            logger.warn("PF-IDENTITY failed: {}", e.getClass().getSimpleName());
            return unknown("PF-IDENTITY", automation ? AUTOMATION_ONLY : List.of(), "Pod-scoped AWS identity");
        }
    }

    private PreflightCheck computeCheck(String account, String region, String clusterName) {
        if (probe == null) {
            return unknown("PF-COMPUTE", List.of(), "Managed nodegroups");
        }
        try {
            boolean ok = probe.hasManagedNodegroupsAndNotAutoMode(account, region, clusterName);
            return new PreflightCheck("PF-COMPUTE", ok ? PreflightCheck.PASS : PreflightCheck.FAIL, List.of(),
                    "Managed nodegroups",
                    ok ? "Cluster has managed nodegroups" : "EKS Auto Mode or no managed nodegroups",
                    ok ? null : new PreflightCheck.Remediation(
                            "Node consolidation needs EKS managed nodegroups.", null));
        } catch (PreflightProbe.ProbeUnknownException e) {
            return unknown("PF-COMPUTE", List.of(), "Managed nodegroups");
        } catch (RuntimeException e) {
            logger.warn("PF-COMPUTE failed: {}", e.getClass().getSimpleName());
            return unknown("PF-COMPUTE", List.of(), "Managed nodegroups");
        }
    }

    private interface BoolCall {
        boolean call();
    }

    private PreflightCheck boolProbe(String id, List<String> blocks, String title, BoolCall call,
                                     String remediation) {
        if (probe == null) {
            return unknown(id, blocks, title);
        }
        try {
            boolean ok = call.call();
            return ok
                    ? new PreflightCheck(id, PreflightCheck.PASS, blocks, title, "OK", null)
                    : new PreflightCheck(id, PreflightCheck.FAIL, blocks, title, "Failed",
                        new PreflightCheck.Remediation(remediation, null));
        } catch (PreflightProbe.ProbeFailedException e) {
            return new PreflightCheck(id, PreflightCheck.FAIL, blocks, title, "Failed",
                    new PreflightCheck.Remediation(remediation, null));
        } catch (PreflightProbe.ProbeUnknownException e) {
            return unknown(id, blocks, title);
        } catch (RuntimeException e) {
            logger.warn("{} failed: {}", id, e.getClass().getSimpleName());
            return unknown(id, blocks, title);
        }
    }

    private PreflightCheck boolProbeNeverBlocks(String id, String title, BoolCall call, String remediation) {
        try {
            boolean ok = call.call();
            return new PreflightCheck(id, ok ? PreflightCheck.PASS : PreflightCheck.WARN, List.of(), title,
                    ok ? "All instances have hop limit >= 2" : "Some instances have hop limit < 2",
                    ok ? null : new PreflightCheck.Remediation(remediation, null));
        } catch (RuntimeException e) {
            return unknown(id, List.of(), title);
        }
    }

    private static PreflightCheck unknown(String id, List<String> blocks, String title) {
        return new PreflightCheck(id, PreflightCheck.UNKNOWN, blocks, title,
                "Could not determine (no AWS access or probe unavailable)", null);
    }

    /** A check blocks the given phase when it FAILs or is UNKNOWN on a phase it is marked to block. */
    static boolean isBlocking(PreflightCheck check, String phase) {
        if (check.blocksPhases() == null || !check.blocksPhases().contains(phase)) {
            return false;
        }
        return PreflightCheck.FAIL.equals(check.result()) || PreflightCheck.UNKNOWN.equals(check.result());
    }

    static double parseVersion(String version) {
        if (version == null) {
            return 0;
        }
        String[] parts = version.replace("v", "").split("\\.");
        try {
            double major = Double.parseDouble(parts[0]);
            double minor = parts.length > 1 ? Double.parseDouble(parts[1]) / 100.0 : 0;
            return major + minor;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Clear the cache (test support / explicit refresh). */
    public void evict(KubernetesClusterConfig config, String clusterName) {
        cache.clear();
    }
}
