package com.xammer.cloud.service.gcp;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.xammer.cloud.domain.BillingSource;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.gcp.GcpBillingStatusDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves which billing source can answer a GCP project's cost queries, and — when none
 * can — why.
 *
 * <p>This exists because the read path used to answer that question with
 * {@code Optional.empty()}. Missing export table, malformed table name, revoked BigQuery
 * permission, unparsed service account key and genuinely-zero spend all collapsed into the
 * same empty list, the controller turned any exception into {@code ok(emptyList())}, and the
 * cost page rendered one undifferentiated "No cost data available" for all of them. A
 * customer who had never enabled billing export saw the same screen as a customer who spent
 * nothing, and support had no way to tell them apart either.
 *
 * <p>Deliberately cheap enough to call on every page load: it uses BigQuery <em>metadata</em>
 * reads ({@code getTable}, {@code listDatasets}) and never issues a query, so it scans no
 * bytes and adds nothing to the customer's BigQuery bill. Results are cached, with a shorter
 * TTL for unhealthy states than healthy ones so that a customer who has just fixed their
 * export does not wait out a long cache to see it.
 *
 * <p>Single authority for table resolution. {@link GcpCostService} delegates here rather than
 * keeping its own copy, because two implementations of "where is the billing table" is how
 * they drift apart.
 */
@Service
@Slf4j
public class GcpBillingStatusService {

    private final GcpClientProvider gcpClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService redisCacheService;

    @Value("${gcp.billing.export-table-name:}")
    private String billingExportTableName;

    private static final String CACHE_PREFIX = "gcp:billing-status:";

    /**
     * Cache minutes for a usable source. Longer because it is the steady state and this is
     * called on every cost page load.
     */
    private static final int READY_TTL_MINUTES = 30;

    /**
     * Cache minutes for every unhealthy state. Short on purpose: these are the states a
     * customer actively fixes, and a long TTL would keep telling them it is still broken
     * after they had corrected it. Mirrors the populated/empty TTL asymmetry in
     * {@link GcpCostService}.
     */
    private static final int UNHEALTHY_TTL_MINUTES = 5;

    public GcpBillingStatusService(GcpClientProvider gcpClientProvider,
            CloudAccountRepository cloudAccountRepository,
            RedisCacheService redisCacheService) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.redisCacheService = redisCacheService;
    }

    // =====================================================================================
    // Public API
    // =====================================================================================

    /**
     * Drops the cached status for a project.
     *
     * <p>Called when the billing source changes — a new export table, a move between billing
     * sources — so the UI stops reporting a problem the operator has already fixed.
     */
    public void evictStatus(String gcpProjectId) {
        if (gcpProjectId == null || gcpProjectId.isBlank()) {
            return;
        }
        redisCacheService.evict(CACHE_PREFIX + gcpProjectId);
    }

    /** Readiness of {@code gcpProjectId}'s cost source, and the reason when it is not ready. */
    public GcpBillingStatusDto getStatus(String gcpProjectId, boolean forceRefresh) {
        if (gcpProjectId == null || gcpProjectId.isBlank()) {
            return GcpBillingStatusDto.unknown(gcpProjectId, null, "No project id supplied");
        }

        String cacheKey = CACHE_PREFIX + gcpProjectId;
        if (forceRefresh) {
            redisCacheService.evict(cacheKey);
        } else {
            Optional<GcpBillingStatusDto> cached = redisCacheService.get(cacheKey, GcpBillingStatusDto.class);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        GcpBillingStatusDto status = resolve(gcpProjectId);

        redisCacheService.put(cacheKey, status,
                status.getState() == GcpBillingStatusDto.State.READY
                        ? READY_TTL_MINUTES
                        : UNHEALTHY_TTL_MINUTES);

        recordAvailabilityFor(gcpProjectId, status);

        if (status.getState() != GcpBillingStatusDto.State.READY) {
            log.warn("GCP billing source for project {} is {} — {}",
                    gcpProjectId, status.getState(),
                    status.getDetail() != null ? status.getDetail() : status.getSummary());
        }
        return status;
    }

    /**
     * Persists availability from the source check rather than from query results.
     *
     * <p>Inferring it from a query would be wrong: a cost-by-label breakdown is legitimately
     * empty on a project that uses no labels, and treating that as "no billing data" would
     * mark a perfectly healthy account unavailable. Whether the export has rows is a property
     * of the source, which is exactly what this class already established.
     *
     * <p>States that say nothing about the source ({@code ACCESS_DENIED},
     * {@code CREDENTIALS_MISSING}, {@code UNKNOWN}) leave the flag untouched — the export may
     * well hold data that we currently cannot see, and overwriting it with false would
     * discard a known-good answer on a transient failure.
     */
    private void recordAvailabilityFor(String gcpProjectId, GcpBillingStatusDto status) {
        switch (status.getState()) {
            case READY -> recordBillingDataAvailability(gcpProjectId, true);
            case EXPORT_NOT_CONFIGURED, EXPORT_TABLE_INVALID, EXPORT_PENDING,
                    CSP_DATASET_UNAVAILABLE -> recordBillingDataAvailability(gcpProjectId, false);
            default -> {
                // Inconclusive — leave the previous answer in place.
            }
        }
    }

    /**
     * The billing export table for a project, or empty when none resolves.
     *
     * <p>Kept so the cost read path has one implementation of table resolution to call. The
     * reason for an empty result is available through {@link #getStatus} rather than being
     * squeezed into this return type, because the read path's behaviour on failure is the
     * same regardless of reason — return no rows — while the UI's is not.
     */
    public Optional<String> resolveExportTable(BigQuery bigquery, String gcpProjectId) {
        TableResolution configured = resolveConfiguredTable(findAccount(gcpProjectId).orElse(null), gcpProjectId);
        if (configured.outcome() == TableResolution.Outcome.FOUND) {
            return Optional.of(configured.tableName());
        }

        // Deliberately more forgiving than the status check: a malformed stored table falls
        // through to discovery here, which is what the read path has always done. Refusing
        // instead would blank a dashboard that a working export could still have filled,
        // and the customer is told about the bad value through the status endpoint anyway.
        if (configured.outcome() == TableResolution.Outcome.MALFORMED) {
            log.warn("Project {}: {} — falling back to discovery for the read path",
                    gcpProjectId, configured.detail());
        }

        TableResolution discovered = discoverTable(bigquery, gcpProjectId);
        if (discovered.outcome() == TableResolution.Outcome.FOUND) {
            return Optional.of(discovered.tableName());
        }

        log.warn("Could not resolve a GCP billing export table for project {}. "
                + "Call /api/xamops/gcp/costs/billing-status for the reason.", gcpProjectId);
        return Optional.empty();
    }

    /**
     * Records whether a project's billing source actually produced data, so the account row
     * reflects reality for the direct-export path too.
     *
     * <p>Previously only the nightly CSP ingestion set this, which meant a direct-export
     * account's {@code billingDataAvailable} stayed null forever and nothing downstream could
     * distinguish "never checked" from "checked, found nothing".
     */
    public void recordBillingDataAvailability(String gcpProjectId, boolean hasData) {
        try {
            cloudAccountRepository.updateBillingDataAvailable(gcpProjectId, hasData);
        } catch (Exception e) {
            // Never fail a cost read over bookkeeping.
            log.warn("Failed to record billing data availability for project {}: {}",
                    gcpProjectId, e.getMessage());
        }
    }

    // =====================================================================================
    // Resolution
    // =====================================================================================

    private GcpBillingStatusDto resolve(String gcpProjectId) {
        Optional<CloudAccount> accountOpt = findAccount(gcpProjectId);
        if (accountOpt.isEmpty()) {
            return GcpBillingStatusDto.unknown(gcpProjectId, null,
                    "No cloud_account row found for GCP project " + gcpProjectId);
        }

        CloudAccount account = accountOpt.get();
        BillingSource source = account.resolveBillingSource();

        // Reseller-billed: costs live in the reseller's dataset, so the project's own export
        // is irrelevant and its absence is not a fault. What matters is whether a CSP config
        // is actually linked — without one the old code read nothing and reported zero.
        if (source.usesCspDataset()) {
            boolean linked = cloudAccountRepository
                    .findCspBillingConfigByGcpProjectId(gcpProjectId)
                    .isPresent();
            return linked
                    ? GcpBillingStatusDto.ready(gcpProjectId, source, null)
                    : GcpBillingStatusDto.cspDatasetUnavailable(gcpProjectId, source);
        }

        Optional<BigQuery> bqOpt;
        try {
            bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        } catch (Exception e) {
            return classifyFailure(gcpProjectId, source, null, e);
        }
        if (bqOpt.isEmpty()) {
            return GcpBillingStatusDto.credentialsMissing(gcpProjectId, source);
        }

        BigQuery bigquery = bqOpt.get();

        // Configuration first. A malformed value is reported rather than silently worked
        // around, because the customer recorded that table on purpose and needs to know it
        // is not the one being read.
        TableResolution resolution = resolveConfiguredTable(account, gcpProjectId);
        if (resolution.outcome() == TableResolution.Outcome.NOT_FOUND) {
            resolution = discoverTable(bigquery, gcpProjectId);
        }

        return switch (resolution.outcome()) {
            case MALFORMED -> GcpBillingStatusDto.exportTableInvalid(
                    gcpProjectId, source, resolution.tableName(), resolution.detail());
            case DENIED -> GcpBillingStatusDto.accessDenied(
                    gcpProjectId, source, resolution.tableName(), resolution.detail());
            case ERROR -> GcpBillingStatusDto.unknown(gcpProjectId, source, resolution.detail());
            case NOT_FOUND -> GcpBillingStatusDto.exportNotConfigured(gcpProjectId, source);
            case FOUND -> inspectTable(bigquery, gcpProjectId, source, resolution.tableName());
        };
    }

    /**
     * Distinguishes a table that exists and has rows from one that is configured but still
     * empty — the normal state for the first day after onboarding, which must not read as a
     * misconfiguration.
     *
     * <p>Uses a metadata read, not a query: row count is already in the table's metadata, so
     * this costs nothing and cannot time out on a large export.
     */
    private GcpBillingStatusDto inspectTable(BigQuery bigquery, String gcpProjectId,
            BillingSource source, String tableName) {
        TableId tableId = parseTableId(tableName);
        if (tableId == null) {
            return GcpBillingStatusDto.exportTableInvalid(gcpProjectId, source, tableName,
                    "Table name '" + tableName + "' is not project.dataset.table");
        }

        try {
            Table table = bigquery.getTable(tableId);
            if (table == null || !table.exists()) {
                return GcpBillingStatusDto.exportTableInvalid(gcpProjectId, source, tableName,
                        "Table " + tableName + " does not exist in BigQuery");
            }

            BigInteger numRows = table.getNumRows();
            if (numRows != null && numRows.signum() == 0) {
                return GcpBillingStatusDto.exportPending(gcpProjectId, source, tableName);
            }
            return GcpBillingStatusDto.ready(gcpProjectId, source, tableName);
        } catch (Exception e) {
            return classifyFailure(gcpProjectId, source, tableName, e);
        }
    }

    /**
     * The table a human configured for this project: the account's recorded value first,
     * then the deployment-wide property. Returns {@code NOT_FOUND} when neither is set,
     * which is the caller's signal to try discovery.
     */
    private TableResolution resolveConfiguredTable(CloudAccount account, String gcpProjectId) {
        if (account != null) {
            String storedTable = account.getBillingExportTable();
            if (storedTable != null && !storedTable.isBlank()) {
                if (isFullyQualified(storedTable)) {
                    log.debug("Using billing table from CloudAccount for project {}: {}",
                            gcpProjectId, storedTable);
                    return TableResolution.found(storedTable.trim());
                }
                return TableResolution.malformed(storedTable,
                        "Stored billing table '" + storedTable
                                + "' is not fully qualified (expected project.dataset.table)");
            }
        }

        if (billingExportTableName != null && !billingExportTableName.isBlank()) {
            if (isFullyQualified(billingExportTableName)) {
                return TableResolution.found(billingExportTableName.trim());
            }
            return TableResolution.malformed(billingExportTableName,
                    "Configured gcp.billing.export-table-name '" + billingExportTableName
                            + "' is not fully qualified (expected project.dataset.table)");
        }

        return TableResolution.notFound();
    }

    /**
     * Scans the project's datasets for a billing export table.
     *
     * <p>Unlike the original read path, a permission failure here is reported as
     * {@code DENIED} rather than logged and flattened into "no table found" — those two
     * states need opposite things from the customer, and telling someone to enable an export
     * they already enabled is worse than telling them nothing.
     */
    private TableResolution discoverTable(BigQuery bigquery, String gcpProjectId) {
        if (bigquery == null) {
            return TableResolution.notFound();
        }
        try {
            for (Dataset dataset : bigquery.listDatasets(gcpProjectId).iterateAll()) {
                for (Table table : bigquery.listTables(dataset.getDatasetId()).iterateAll()) {
                    String tableId = table.getTableId().getTable();
                    if (tableId.startsWith("gcp_billing_export_v1")
                            || tableId.startsWith("gcp_billing_export_resource_v1")) {
                        String fullName = String.format("%s.%s.%s",
                                table.getTableId().getProject(),
                                table.getTableId().getDataset(),
                                tableId);
                        log.info("Discovered GCP billing export table for project {}: {}",
                                gcpProjectId, fullName);
                        return TableResolution.found(fullName);
                    }
                }
            }
        } catch (Exception e) {
            String detail = rootMessage(e);
            return isAccessDenied(e)
                    ? TableResolution.denied(detail)
                    : TableResolution.error(detail);
        }
        return TableResolution.notFound();
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private Optional<CloudAccount> findAccount(String gcpProjectId) {
        try {
            return cloudAccountRepository.findByGcpProjectId(gcpProjectId);
        } catch (Exception e) {
            log.warn("Failed to load cloud account for GCP project {}: {}", gcpProjectId, e.getMessage());
            return Optional.empty();
        }
    }

    private GcpBillingStatusDto classifyFailure(String gcpProjectId, BillingSource source,
            String tableName, Exception e) {
        String detail = rootMessage(e);
        if (isAccessDenied(e)) {
            return GcpBillingStatusDto.accessDenied(gcpProjectId, source, tableName, detail);
        }
        return GcpBillingStatusDto.unknown(gcpProjectId, source, detail);
    }

    /** A billing export table is always {@code project.dataset.table}. */
    private static boolean isFullyQualified(String tableName) {
        if (tableName == null) {
            return false;
        }
        String trimmed = tableName.trim();
        if (trimmed.chars().filter(c -> c == '.').count() != 2) {
            return false;
        }
        for (String part : trimmed.split("\\.")) {
            if (part.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static TableId parseTableId(String tableName) {
        if (!isFullyQualified(tableName)) {
            return null;
        }
        String[] parts = tableName.trim().split("\\.");
        return TableId.of(parts[0], parts[1], parts[2]);
    }

    /**
     * True when a failure is an authorization problem rather than a missing resource.
     *
     * <p>Checks the HTTP/gRPC status where the client exposes one and falls back to message
     * matching, because BigQuery surfaces denials through several exception types depending
     * on whether the call went through the REST or gapic layer.
     */
    private static boolean isAccessDenied(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.google.cloud.BaseServiceException serviceException
                    && serviceException.getCode() == 403) {
                return true;
            }
            if (cause instanceof com.google.api.gax.rpc.PermissionDeniedException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("access denied")
                        || lower.contains("permission denied")
                        || lower.contains("forbidden")
                        || lower.contains("does not have permission")
                        || lower.contains("caller does not have")
                        || lower.contains("user does not have bigquery")) {
                    return true;
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null ? message : cause.getClass().getSimpleName();
    }

    /** Outcome of looking for a project's export table, carrying why it failed. */
    private record TableResolution(Outcome outcome, String tableName, String detail) {

        enum Outcome {
            /** A usable, fully-qualified table name was resolved. */
            FOUND,
            /** Nothing configured and nothing discoverable. */
            NOT_FOUND,
            /** A table is recorded but is not a valid fully-qualified name. */
            MALFORMED,
            /** BigQuery refused the lookup. */
            DENIED,
            /** Anything else went wrong. */
            ERROR
        }

        static TableResolution found(String tableName) {
            return new TableResolution(Outcome.FOUND, tableName, null);
        }

        static TableResolution notFound() {
            return new TableResolution(Outcome.NOT_FOUND, null, null);
        }

        static TableResolution malformed(String tableName, String detail) {
            return new TableResolution(Outcome.MALFORMED, tableName, detail);
        }

        static TableResolution denied(String detail) {
            return new TableResolution(Outcome.DENIED, null, detail);
        }

        static TableResolution error(String detail) {
            return new TableResolution(Outcome.ERROR, null, detail);
        }
    }
}
