package com.xammer.cloud.service.war;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.dto.war.WarStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Mutable, thread-safe accumulator for one WAR scan of one account.
 *
 * <p>
 * Check classes run concurrently across regions and services, each writing results
 * keyed by control id. The context exists so a check can fail — a denied IAM action,
 * a region that times out, a service that isn't enabled — without taking the whole
 * scan down with it. A partial WAR report that says "58 controls verified, 6 blocked
 * on missing permissions" is useful; a 500 is not.
 */
public class WarScanContext {

    private static final Logger log = LoggerFactory.getLogger(WarScanContext.class);

    private final CloudAccount account;
    private final List<String> regions;

    private final Map<String, WarCheckResult> results = new ConcurrentHashMap<>();

    /** Actions the role was explicitly denied — a real IAM policy gap. */
    private final Set<String> deniedActions = ConcurrentHashMap.newKeySet();

    /**
     * Actions that failed for a reason other than authorization: expired credentials,
     * unreachable endpoints, a disabled service. Granting these would fix nothing.
     */
    private final Set<String> blockedActions = ConcurrentHashMap.newKeySet();
    private final List<String> warnings = Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Why a control could not be evaluated, when no region produced a verdict.
     * Without this a blocked control reports "not evaluated" and the customer has to
     * go digging for the cause that the scan already knew.
     */
    private final Map<String, String> blockedReasons = new ConcurrentHashMap<>();

    /** Root failure message to occurrence count, used to identify a single dominant cause. */
    private final Map<String, Integer> failureTally = new ConcurrentHashMap<>();

    public WarScanContext(CloudAccount account, List<String> regions) {
        this.account = account;
        this.regions = regions;
    }

    public CloudAccount account() {
        return account;
    }

    /**
     * The provider's own identifier for this account: AWS account number, GCP project
     * id, or Azure subscription id.
     *
     * <p>
     * This must not be {@code getAwsAccountId()}. That column is null on a GCP account,
     * and passing null down to the client provider made every GCP check look up
     * credentials for "no project" — which matched every account with a null project id
     * and failed with a non-unique-result error that looked like a data problem.
     */
    public String accountId() {
        return account.getProviderAccountId();
    }

    public List<String> regions() {
        return regions;
    }

    public void put(String controlId, WarCheckResult result) {
        results.put(controlId, result);
    }

    /**
     * Merge a per-region result into a control that spans regions.
     *
     * <p>
     * Severity wins: one region with an open SSH port makes the control FAIL even if
     * the other five are clean, because an attacker only needs the one. Evidence from
     * every region is concatenated so the UI can show which region to fix.
     */
    public void merge(String controlId, WarCheckResult incoming) {
        results.merge(controlId, incoming, (existing, added) -> {
            existing.getEvidence().addAll(added.getEvidence());
            existing.setAffectedCount(existing.getEvidence().size());
            if (rank(added.getStatus()) > rank(existing.getStatus())) {
                existing.setStatus(added.getStatus());
                existing.setSummary(added.getSummary());
            }
            return existing;
        });
    }

    private static int rank(WarStatus status) {
        return switch (status) {
            case FAIL -> 5;
            case WARN -> 4;
            case UNKNOWN -> 3;
            case MANUAL -> 2;
            case PASS -> 1;
            case NOT_APPLICABLE -> 0;
        };
    }

    /**
     * Run a check, recording UNKNOWN against every control it covers if it throws.
     *
     * @param iamAction  the IAM action the check needs, surfaced to the customer when denied.
     * @param controlIds the controls this check decides.
     */
    public void guard(String iamAction, List<String> controlIds, Runnable check) {
        try {
            check.run();
        } catch (Exception e) {
            String reason = rootMessage(e);
            log.warn("[WAR] check for {} failed on account {} ({}): {}",
                    controlIds, accountId(), iamAction, reason);
            recordFailure(iamAction, reason);
            for (String id : controlIds) {
                results.putIfAbsent(id, WarCheckResult.unknown(
                        "Could not verify — " + reason, iamAction));
            }
        }
    }

    /**
     * Region-scoped variant.
     *
     * @param controlIds controls this check decides. If no region ever produces a
     *                   verdict for them, they surface as UNKNOWN carrying this
     *                   failure reason rather than a bare "not evaluated".
     */
    public void guardRegion(String region, String iamAction, List<String> controlIds, Runnable check) {
        try {
            check.run();
        } catch (Exception e) {
            String reason = rootMessage(e);
            log.warn("[WAR] {} check failed in {} for account {}: {}",
                    iamAction, region, accountId(), reason);
            recordFailure(iamAction, reason);
            warnings.add(String.format("%s in %s: %s", iamAction, region, reason));
            for (String id : controlIds) {
                // Only the reason is stored; whether it wins is decided at assembly,
                // so a region that succeeded still gets to set the real verdict.
                blockedReasons.putIfAbsent(id, reason);
            }
        }
    }

    private void recordFailure(String iamAction, String reason) {
        if (isAuthorizationFailure(reason)) {
            deniedActions.add(iamAction);
        } else {
            blockedActions.add(iamAction);
        }
        failureTally.merge(reason, 1, (a, b) -> a + b);
    }

    /**
     * Distinguishes "the role is missing this permission" from "the call never got far
     * enough to be authorized".
     *
     * <p>
     * This matters because the two have opposite remedies. Telling a customer to grant
     * 34 IAM actions when their credentials have simply expired sends them to rewrite a
     * policy that was never the problem.
     */
    private static boolean isAuthorizationFailure(String reason) {
        if (reason == null) {
            return false;
        }
        String lower = reason.toLowerCase();
        return lower.contains("not authorized")
                || lower.contains("accessdenied")
                || lower.contains("access denied")
                || lower.contains("explicit deny")
                || lower.contains("no identity-based policy");
    }

    /** Returns {@code fallback} instead of throwing, for optional sub-lookups inside a check. */
    public <T> T quiet(T fallback, Supplier<T> call) {
        try {
            return call.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        // AWS error messages carry a long request-id tail that is noise in a UI card.
        int marker = msg.indexOf(" (Service:");
        return marker > 0 ? msg.substring(0, marker) : msg;
    }

    public Map<String, WarCheckResult> results() {
        return results;
    }

    public List<String> deniedActions() {
        return deniedActions.stream().sorted().toList();
    }

    public List<String> blockedActions() {
        return blockedActions.stream().sorted().toList();
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public Map<String, String> blockedReasons() {
        return Map.copyOf(blockedReasons);
    }

    /**
     * The single failure message behind most of the scan, if there is one.
     *
     * <p>
     * When credentials expire, every check fails for the same reason and the page
     * should say so once, in plain terms, instead of listing a hundred identical
     * warnings and leaving the reader to spot the pattern.
     */
    public Optional<String> dominantFailure() {
        int total = failureTally.values().stream().mapToInt(v -> v).sum();
        if (total < 3) {
            return Optional.empty();
        }
        return failureTally.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(e -> e.getValue() * 2 > total)
                .map(e -> e.getKey());
    }
}
