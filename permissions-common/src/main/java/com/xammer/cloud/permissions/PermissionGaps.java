package com.xammer.cloud.permissions;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builders for {@link PermissionGap}, so callers never have to remember to fill
 * in the feature mapping or the display label by hand — a gap missing those
 * reaches the customer as a bare error code with no explanation of what broke.
 */
public final class PermissionGaps {

    private PermissionGaps() {
    }

    /** A specific API action the role is not allowed to call. */
    public static PermissionGap denied(String accountId, String servicePrefix, String operation,
            String region, String errorCode, String message, PermissionGap.Kind kind) {

        PermissionGap gap = new PermissionGap();
        gap.setAccountId(accountId);
        gap.setProvider("AWS");
        gap.setKind(kind != null ? kind : PermissionGap.Kind.PERMISSION);
        gap.setService(servicePrefix);
        gap.setServiceLabel(AwsPermissionCatalog.labelFor(servicePrefix));
        gap.setOperation(operation);
        gap.setAction(servicePrefix + ":" + (operation != null ? operation : "*"));
        gap.setErrorCode(errorCode);
        gap.setMessage(trimMessage(message));
        gap.setFirstSeen(Instant.now());
        gap.setLastSeen(Instant.now());
        gap.setOccurrences(1);

        if (region != null && !region.isBlank()) {
            Set<String> regions = new LinkedHashSet<>();
            regions.add(region);
            gap.setRegions(regions);
        }

        Set<XamOpsFeature> impacted = AwsPermissionCatalog.featuresFor(servicePrefix);
        gap.setFeatures(impacted.stream().map(Enum::name).collect(Collectors.toList()));
        gap.setFeatureLabels(AwsPermissionCatalog.featureLabels(impacted));
        return gap;
    }

    /**
     * The cross-account role itself cannot be assumed — deleted, renamed, or its
     * trust policy no longer trusts XamOps. Nothing loads for this account.
     */
    public static PermissionGap roleUnreachable(String accountId, String errorCode, String message) {
        return denied(accountId, "sts", "AssumeRole", null, errorCode, message,
                PermissionGap.Kind.ROLE_UNREACHABLE);
    }

    /** AWS denial messages trail a service/request-id suffix that carries no meaning for a customer. */
    static String trimMessage(String message) {
        if (message == null) {
            return null;
        }
        String cleaned = message.replaceAll("\\s*\\(Service:.*", "").trim();
        return cleaned.length() > 400 ? cleaned.substring(0, 400) + "…" : cleaned;
    }
}
