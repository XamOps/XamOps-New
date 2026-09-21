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

    // ── GCP ──────────────────────────────────────────────────────────────────

    /**
     * A GCP permission the project's service account does not hold.
     *
     * @param permission the fine-grained IAM permission, e.g. {@code compute.instances.list}.
     *                   GCP has no service:Action split, so this doubles as the action and is
     *                   reduced to its service prefix for the catalog lookup.
     */
    public static PermissionGap gcpDenied(String projectId, String permission, String errorCode,
            String message, PermissionGap.Kind kind) {

        String service = GcpPermissionCatalog.serviceFromPermission(permission);
        PermissionGap gap = base(projectId, "GCP", kind);
        gap.setService(service);
        gap.setServiceLabel(GcpPermissionCatalog.labelFor(service));
        gap.setOperation(permission);
        // Already fully qualified — unlike AWS there is nothing to join.
        gap.setAction(permission);
        gap.setErrorCode(errorCode);
        gap.setMessage(trimMessage(message));

        Set<XamOpsFeature> impacted = GcpPermissionCatalog.featuresFor(service);
        gap.setFeatures(impacted.stream().map(Enum::name).collect(Collectors.toList()));
        gap.setFeatureLabels(GcpPermissionCatalog.featureLabels(impacted));
        return gap;
    }

    /**
     * The project's service-account key no longer works — deleted, disabled, or the key
     * material was rotated away. Nothing loads for this project, so this is the GCP
     * counterpart of an unassumable AWS role rather than a per-permission gap.
     */
    public static PermissionGap gcpUnreachable(String projectId, String errorCode, String message) {
        return gcpDenied(projectId, "iam.serviceAccounts.getAccessToken", errorCode, message,
                PermissionGap.Kind.ROLE_UNREACHABLE);
    }

    // ── Azure ────────────────────────────────────────────────────────────────

    /**
     * An Azure RBAC action the service principal is not assigned.
     *
     * @param action the ARM action, e.g. {@code Microsoft.Compute/virtualMachines/read}
     */
    public static PermissionGap azureDenied(String subscriptionId, String action, String errorCode,
            String message, PermissionGap.Kind kind) {

        String provider = AzurePermissionCatalog.providerFromAction(action);
        PermissionGap gap = base(subscriptionId, "Azure", kind);
        gap.setService(provider);
        gap.setServiceLabel(AzurePermissionCatalog.labelFor(provider));
        gap.setOperation(action);
        gap.setAction(action);
        gap.setErrorCode(errorCode);
        gap.setMessage(trimMessage(message));

        Set<XamOpsFeature> impacted = AzurePermissionCatalog.featuresFor(provider);
        gap.setFeatures(impacted.stream().map(Enum::name).collect(Collectors.toList()));
        gap.setFeatureLabels(AzurePermissionCatalog.featureLabels(impacted));
        return gap;
    }

    /**
     * The service principal itself cannot authenticate — expired client secret, wrong tenant,
     * or the app registration was removed.
     */
    public static PermissionGap azureUnreachable(String subscriptionId, String errorCode, String message) {
        return azureDenied(subscriptionId, "Microsoft.Resources/subscriptions/read", errorCode, message,
                PermissionGap.Kind.ROLE_UNREACHABLE);
    }

    /**
     * The fields every provider sets identically.
     *
     * <p>Sets {@code provider} explicitly rather than relying on the field initialiser, which
     * defaults to {@code "AWS"} — a GCP gap that forgot to overwrite it would be labelled AWS
     * and round-trip through Redis that way, showing the customer the wrong cloud.
     */
    private static PermissionGap base(String accountId, String provider, PermissionGap.Kind kind) {
        PermissionGap gap = new PermissionGap();
        gap.setAccountId(accountId);
        gap.setProvider(provider);
        gap.setKind(kind != null ? kind : PermissionGap.Kind.PERMISSION);
        gap.setFirstSeen(Instant.now());
        gap.setLastSeen(Instant.now());
        gap.setOccurrences(1);
        return gap;
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
