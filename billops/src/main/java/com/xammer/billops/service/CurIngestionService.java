package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.cur.CurAggregates;
import com.xammer.billops.repository.BillOpsCachedDataRepository;
import com.xammer.cloud.domain.CachedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Reads a customer's AWS Cost and Usage Report (CUR 2.0) export from their own S3
 * bucket and folds it into bounded monthly aggregates in the shared
 * {@code cached_data} table.
 *
 * WHY THIS EXISTS
 * Cost Explorer is an aggregation layer over AWS's billing data, and its totals
 * routinely fail to reconcile with the actual invoice: provisional figures for the
 * last 24-48h, amortisation choices, and credits/refunds/tax distributed across
 * dimensions in ways that do not map to invoice lines. CUR is the source AWS
 * generates the invoice from, so every one of those is a native column. BillOps
 * issues customer-facing invoices, so it needs the reconciled number, not an
 * estimate.
 *
 * WHAT THIS DOES NOT DO
 * It does not replace Cost Explorer. The current (open) month has no final CUR, so
 * CostService keeps serving it from CE exactly as before. This class only owns
 * closed months, and only for accounts that actually have a CUR export configured.
 * Everything here is additive: with no export configured, or an export that fails
 * to parse, every read falls back to the pre-existing CE path untouched.
 *
 * KEY SCHEME (parallel to, never overwriting, the CE keys xamops-service writes)
 *   AWS_CUR_TOTAL::{accountId}::{YYYY-MM}       → Double
 *   AWS_CUR_DAILY::{accountId}::{YYYY-MM}       → List&lt;DailyEntry&gt;
 *   AWS_CUR_SVC_REGION::{accountId}::{YYYY-MM}  → List&lt;SvcRegionEntry&gt;
 *   AWS_CUR_SVC_USAGE::{accountId}::{YYYY-MM}   → List&lt;SvcUsageEntry&gt;
 *   AWS_CUR_STATE::{accountId}::{YYYY-MM}       → ingest metadata, written LAST
 *
 * AWS_CUR_STATE is the completeness sentinel: it is persisted only after all four
 * data keys have been written, so a run that dies halfway can never leave a
 * partially-populated month that reads as authoritative.
 */
@Service
public class CurIngestionService {

    private static final Logger logger = LoggerFactory.getLogger(CurIngestionService.class);

    private static final String K_TOTAL      = "AWS_CUR_TOTAL::";
    private static final String K_DAILY      = "AWS_CUR_DAILY::";
    private static final String K_SVC_REGION = "AWS_CUR_SVC_REGION::";
    private static final String K_SVC_USAGE  = "AWS_CUR_SVC_USAGE::";
    private static final String K_STATE      = "AWS_CUR_STATE::";

    /**
     * Rows whose absolute cost is below this are dropped from the usage-type
     * breakdown. CUR emits a row per usage type per day, so a month contains a long
     * tail of sub-cent rows that inflate the stored aggregate without changing any
     * displayed figure. The monthly total is summed BEFORE this filter, so the
     * reconciled headline number is unaffected.
     *
     * A row is kept when it has EITHER meaningful cost OR any usage quantity — see
     * {@link #isBillableRow}.
     */
    private static final double USAGE_NOISE_FLOOR = 0.0001;

    /**
     * Whether a CUR row belongs in the usage-type breakdown.
     *
     * Cost alone is not a sufficient test once usage quantity is itself billable.
     * BillOps prices CloudFront from private per-GB rates, meaning the GB figure —
     * not AWS's cost — generates the invoice line. CloudFront also includes a 1 TB/month
     * free data-transfer tier, whose rows arrive with a real quantity and a cost of
     * zero. Filtering on cost alone would silently discard up to a terabyte of
     * billable transfer every month, which is lost revenue rather than a display
     * glitch, so quantity keeps a row too.
     */
    private static boolean isBillableRow(double cost, double quantity) {
        return Math.abs(cost) >= USAGE_NOISE_FLOOR || quantity != 0.0;
    }

    private final AwsClientProvider awsClientProvider;
    private final BillOpsCachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    @Value("${billops.cur.enabled:true}")
    private boolean curEnabled;

    public CurIngestionService(AwsClientProvider awsClientProvider,
                               BillOpsCachedDataRepository cachedDataRepository,
                               ObjectMapper objectMapper) {
        this.awsClientProvider = awsClientProvider;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------------
    // Key helpers
    // ------------------------------------------------------------------------

    public static String keyTotal(String accountId, YearMonth ym)     { return K_TOTAL + accountId + "::" + ym; }
    public static String keyDaily(String accountId, YearMonth ym)     { return K_DAILY + accountId + "::" + ym; }
    public static String keySvcRegion(String accountId, YearMonth ym) { return K_SVC_REGION + accountId + "::" + ym; }
    public static String keySvcUsage(String accountId, YearMonth ym)  { return K_SVC_USAGE + accountId + "::" + ym; }
    public static String keyState(String accountId, YearMonth ym)     { return K_STATE + accountId + "::" + ym; }

    /** True when this account has a CUR export configured at all. */
    public boolean isConfigured(CloudAccount account) {
        return curEnabled
                && account != null
                && account.getCurS3Bucket() != null && !account.getCurS3Bucket().isBlank();
    }

    // ------------------------------------------------------------------------
    // Configuration discovery
    // ------------------------------------------------------------------------

    /**
     * The bucket name the onboarding stack's nested CUR template creates.
     *
     * Deriving it rather than reading it back from the stack outputs is deliberate:
     * DescribeStacks would need a cloudformation:* grant that the read-only
     * onboarding role does not have, and would tie BillOps to the stack still
     * existing. The template hardcodes this name, so it is knowable — and if the
     * bucket is absent or unreadable, probing it fails cleanly and the account
     * simply stays on Cost Explorer.
     *
     * Keep in step with CurExportBucket.BucketName in
     * cloudformation/xamops-cur-export-nested.yaml.
     */
    public static String conventionalBucket(String awsAccountId) {
        return "xamops-cur-" + awsAccountId;
    }

    /** Matches S3Prefix in the nested CUR template. */
    public static final String CONVENTIONAL_PREFIX = "cur2";

    /**
     * Probes whether the conventional CUR bucket exists and is readable through the
     * account's assumed role, and if so returns {bucket, prefix} to persist.
     *
     * Used at account verification and again on each sweep, which is what lets an
     * already-connected customer pick up CUR simply by re-running their stack — no
     * re-onboarding, no support ticket.
     */
    public Optional<String[]> detectCurConfig(CloudAccount account) {
        if (!curEnabled || account == null || account.getAwsAccountId() == null) {
            return Optional.empty();
        }
        String bucket = conventionalBucket(account.getAwsAccountId());
        try {
            S3Client s3 = s3For(account, bucket);
            // One cheap call. A missing bucket or a role without s3:ListBucket on it
            // both land here as an exception, which is the answer we want either way.
            s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(CONVENTIONAL_PREFIX)
                    .maxKeys(1)
                    .build());
            logger.info("[CUR] Detected export bucket for account {}: s3://{}/{}",
                    account.getAwsAccountId(), bucket, CONVENTIONAL_PREFIX);
            return Optional.of(new String[]{bucket, CONVENTIONAL_PREFIX});
        } catch (Exception e) {
            logger.debug("[CUR] No readable export bucket for {} ({})",
                    account.getAwsAccountId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * True when a complete CUR aggregate exists for this account/month.
     *
     * Callers use this to decide whether to prefer CUR over Cost Explorer. It checks
     * the sentinel only, so a half-written month reads as absent.
     */
    public boolean hasCurData(String accountId, YearMonth ym) {
        if (!curEnabled || accountId == null) return false;
        try {
            return cachedDataRepository.existsById(keyState(accountId, ym));
        } catch (Exception e) {
            logger.debug("[CUR] State check failed for {} {}: {}", accountId, ym, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // Read helpers — used by the BillOps read path
    // ------------------------------------------------------------------------

    public Optional<Double> readTotal(String accountId, YearMonth ym) {
        return read(keyTotal(accountId, ym), new TypeReference<Double>() {});
    }

    public Optional<List<CurAggregates.DailyEntry>> readDaily(String accountId, YearMonth ym) {
        return read(keyDaily(accountId, ym), new TypeReference<List<CurAggregates.DailyEntry>>() {});
    }

    public Optional<List<CurAggregates.SvcRegionEntry>> readSvcRegion(String accountId, YearMonth ym) {
        return read(keySvcRegion(accountId, ym), new TypeReference<List<CurAggregates.SvcRegionEntry>>() {});
    }

    public Optional<List<CurAggregates.SvcUsageEntry>> readSvcUsage(String accountId, YearMonth ym) {
        return read(keySvcUsage(accountId, ym), new TypeReference<List<CurAggregates.SvcUsageEntry>>() {});
    }

    private <T> Optional<T> read(String key, TypeReference<T> typeRef) {
        try {
            return cachedDataRepository.findById(key).map(row -> {
                try {
                    return objectMapper.<T>readValue(row.getJsonData(), typeRef);
                } catch (Exception e) {
                    logger.warn("[CUR] Deserialize failed for {}: {}", key, e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            logger.warn("[CUR] DB read failed for {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------
    // Ingestion
    // ------------------------------------------------------------------------

    /**
     * Discovers, parses and persists the CUR export for one account and billing period.
     *
     * Returns empty when there is nothing to do — no export configured, no manifest
     * for that period yet, or an already-ingested period whose export has not been
     * re-delivered. Never throws for an individual account: a customer with a broken
     * or missing export must not fail the whole scheduled sweep, and BillOps still
     * has the Cost Explorer path to fall back to.
     */
    public Optional<CurAggregates> ingestMonth(CloudAccount account, YearMonth ym, boolean force) {
        if (!isConfigured(account)) {
            return Optional.empty();
        }
        String accountId = account.getAwsAccountId();
        String bucket = account.getCurS3Bucket();
        String prefix = normalisePrefix(account.getCurReportPath());

        try {
            S3Client s3 = s3For(account, bucket);

            Optional<String> manifestKey = findManifestKey(s3, bucket, prefix, ym);
            List<String> dataFileKeys;
            String assemblyId;

            if (manifestKey.isPresent()) {
                ManifestInfo info = readManifest(s3, bucket, manifestKey.get());
                dataFileKeys = info.dataFileKeys;
                assemblyId = info.assemblyId;
            } else {
                // No manifest. Fall back to listing the data directory directly: a
                // manifest is normal for both legacy CUR and Data Exports, but an
                // export configured without INCLUDE_MANIFEST still writes usable
                // data files and should not be silently unreadable.
                dataFileKeys = listDataFiles(s3, bucket, prefix, ym);
                assemblyId = "no-manifest:" + dataFileKeys.size();
                if (dataFileKeys.isEmpty()) {
                    logger.info("[CUR] No export found for {} {} under s3://{}/{}", accountId, ym, bucket, prefix);
                    return Optional.empty();
                }
            }

            if (dataFileKeys.isEmpty()) {
                logger.info("[CUR] Manifest for {} {} lists no data files", accountId, ym);
                return Optional.empty();
            }

            // Idempotency. SYNCHRONOUS refresh re-delivers the same period up to three
            // times a day, so without this every sweep would re-download and re-parse
            // months that have not changed.
            if (!force) {
                Optional<CurIngestState> existing = read(keyState(accountId, ym),
                        new TypeReference<CurIngestState>() {});
                if (existing.isPresent() && assemblyId.equals(existing.get().getAssemblyId())) {
                    logger.debug("[CUR] {} {} already ingested (assembly {}), skipping", accountId, ym, assemblyId);
                    return Optional.empty();
                }
            }

            logger.info("[CUR] Ingesting {} {} — {} data file(s) from s3://{}/{}",
                    accountId, ym, dataFileKeys.size(), bucket, prefix);

            CurAggregates aggregates = aggregate(s3, bucket, dataFileKeys);
            aggregates.setAssemblyId(assemblyId);
            aggregates.setBillingPeriod(ym.toString());

            persistAll(accountId, ym, aggregates);

            logger.info("[CUR] ✅ {} {} — total=${}, {} rows, {} svc/region, {} usage types",
                    accountId, ym, String.format("%.2f", aggregates.getTotal()), aggregates.getRowCount(),
                    aggregates.getSvcRegion().size(), aggregates.getSvcUsage().size());

            return Optional.of(aggregates);

        } catch (Exception e) {
            logger.warn("[CUR] ❌ Ingest failed for {} {}: {}", accountId, ym, e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------
    // S3 discovery
    // ------------------------------------------------------------------------

    /**
     * Builds an S3 client in the bucket's own region.
     *
     * GetBucketLocation is attempted first (the onboarding role grants it); on
     * failure we fall back to us-east-1 rather than aborting, since a same-region
     * bucket still works and a wrong guess surfaces as a clear redirect error
     * rather than silent bad data.
     */
    private S3Client s3For(CloudAccount account, String bucket) {
        String region = "us-east-1";
        try (S3Client probe = awsClientProvider.getS3Client(account, "us-east-1")) {
            String located = probe.getBucketLocation(
                    GetBucketLocationRequest.builder().bucket(bucket).build())
                    .locationConstraintAsString();
            // An empty constraint means us-east-1 — a legacy S3 quirk, not an error.
            if (located != null && !located.isBlank()) {
                region = located;
            }
        } catch (Exception e) {
            logger.debug("[CUR] GetBucketLocation failed for {} ({}), assuming us-east-1", bucket, e.getMessage());
        }
        return awsClientProvider.getS3Client(account, region);
    }

    /**
     * Finds the manifest for a billing period.
     *
     * Deliberately discovery-based rather than composing a key from a known layout.
     * Data Exports writes
     * {@code {prefix}/{exportName}/metadata/BILLING_PERIOD=YYYY-MM/{exportName}-Manifest.json}
     * while legacy CUR uses {@code {prefix}/{report}/YYYYMMDD-YYYYMMDD/{report}-Manifest.json},
     * and the export name is chosen by the stack, not by us. Listing and matching
     * covers both without hardcoding a name that would silently return nothing if it
     * ever changed — the exact failure mode of the placeholder this replaces.
     */
    private Optional<String> findManifestKey(S3Client s3, String bucket, String prefix, YearMonth ym) {
        String billingPeriodTag = "BILLING_PERIOD=" + ym;                    // Data Exports / CUR 2.0
        String legacyRange = ym.atDay(1).toString().replace("-", "")
                + "-" + ym.plusMonths(1).atDay(1).toString().replace("-", ""); // legacy CUR, e.g. 20260801-20260901

        List<String> candidates = new ArrayList<>();
        for (S3Object obj : listAll(s3, bucket, prefix)) {
            String key = obj.key();
            if (!key.endsWith("Manifest.json")) continue;
            if (key.contains(billingPeriodTag) || key.contains(legacyRange)) {
                candidates.add(key);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // Longest key wins. With OVERWRITE_REPORT there is normally exactly one, but a
        // bucket that also holds an older CREATE_NEW_REPORT export can contain several;
        // the deeper path is the versioned one.
        candidates.sort(Comparator.comparingInt(String::length).reversed());
        return Optional.of(candidates.get(0));
    }

    /** Fallback for exports written without a manifest. */
    private List<String> listDataFiles(S3Client s3, String bucket, String prefix, YearMonth ym) {
        String billingPeriodTag = "BILLING_PERIOD=" + ym;
        String legacyRange = ym.atDay(1).toString().replace("-", "")
                + "-" + ym.plusMonths(1).atDay(1).toString().replace("-", "");

        List<String> keys = new ArrayList<>();
        for (S3Object obj : listAll(s3, bucket, prefix)) {
            String key = obj.key();
            boolean parseable = key.endsWith(".csv.gz") || key.endsWith(".csv") || key.endsWith(".gz");
            if (!parseable) continue;
            if (key.contains(billingPeriodTag) || key.contains(legacyRange)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private List<S3Object> listAll(S3Client s3, String bucket, String prefix) {
        List<S3Object> out = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .maxKeys(1000);
            if (prefix != null && !prefix.isBlank()) {
                req.prefix(prefix);
            }
            if (token != null) {
                req.continuationToken(token);
            }
            ListObjectsV2Response resp = s3.listObjectsV2(req.build());
            if (resp.contents() != null) {
                out.addAll(resp.contents());
            }
            token = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
        } while (token != null);
        return out;
    }

    /**
     * Extracts data file keys and an assembly identifier from a manifest.
     *
     * Handles both manifest dialects: Data Exports uses {@code dataFiles} (objects
     * carrying a key), legacy CUR uses {@code reportKeys} (bare strings).
     */
    private ManifestInfo readManifest(S3Client s3, String bucket, String manifestKey) throws Exception {
        String json;
        try (InputStream in = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(manifestKey).build())) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonNode root = objectMapper.readTree(json);
        List<String> keys = new ArrayList<>();

        JsonNode dataFiles = root.get("dataFiles");
        if (dataFiles != null && dataFiles.isArray()) {
            for (JsonNode node : dataFiles) {
                // Data Exports nests the key; tolerate either a bare string or an object.
                if (node.isTextual()) {
                    keys.add(stripS3Uri(node.asText(), bucket));
                } else if (node.has("key")) {
                    keys.add(stripS3Uri(node.get("key").asText(), bucket));
                }
            }
        }

        if (keys.isEmpty()) {
            JsonNode reportKeys = root.get("reportKeys");
            if (reportKeys != null && reportKeys.isArray()) {
                for (JsonNode node : reportKeys) {
                    keys.add(stripS3Uri(node.asText(), bucket));
                }
            }
        }

        // assemblyId changes each time AWS re-delivers the period, which is exactly
        // what makes it a usable idempotency token. Fall back to the manifest's own
        // content hash when absent, so re-ingest detection still works.
        String assemblyId = root.hasNonNull("assemblyId")
                ? root.get("assemblyId").asText()
                : Integer.toHexString(json.hashCode());

        return new ManifestInfo(keys, assemblyId);
    }

    /** Manifests sometimes carry fully-qualified s3:// URIs; the SDK wants a bare key. */
    private String stripS3Uri(String value, String bucket) {
        if (value == null) return "";
        String v = value.trim();
        if (v.startsWith("s3://")) {
            v = v.substring(5);
            if (v.startsWith(bucket + "/")) {
                v = v.substring(bucket.length() + 1);
            }
        }
        return v.startsWith("/") ? v.substring(1) : v;
    }

    private String normalisePrefix(String path) {
        if (path == null || path.isBlank()) return "";
        String p = path.trim();
        if (p.startsWith("/")) p = p.substring(1);
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    // ------------------------------------------------------------------------
    // Parsing and aggregation
    // ------------------------------------------------------------------------

    /**
     * Streams every data file and folds rows into bounded aggregates.
     *
     * Files are streamed and decompressed on the fly and never collected into a
     * list of rows — a busy account's monthly export runs to hundreds of thousands
     * of line items, so materialising them would be the one change here capable of
     * exhausting heap on the BillOps container.
     */
    private CurAggregates aggregate(S3Client s3, String bucket, List<String> keys) throws Exception {
        Accumulator acc = new Accumulator();

        for (String key : keys) {
            try (InputStream raw = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
                InputStream body = key.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                foldFile(body, acc, key);
            }
        }

        return acc.toAggregates();
    }

    /**
     * Folds a single decompressed CUR file into the accumulator.
     *
     * Separated from S3 access so the parsing and aggregation rules — which decide
     * what a customer's invoice says — can be tested directly against sample CUR
     * text rather than only through a live export.
     */
    void foldFile(InputStream body, Accumulator acc, String sourceName) throws Exception {
        try (CSVReader reader = new CSVReader(
                new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)))) {

            String[] header = reader.readNext();
            if (header == null) {
                logger.warn("[CUR] Empty file, skipping: {}", sourceName);
                return;
            }
            ColumnIndex cols = new ColumnIndex(header);
            if (!cols.isUsable()) {
                logger.warn("[CUR] {} has no recognisable cost column — skipping. Header sample: {}",
                        sourceName, String.join(",", java.util.Arrays.copyOf(header, Math.min(header.length, 8))));
                return;
            }

            String[] row;
            while ((row = reader.readNext()) != null) {
                double cost = cols.getDouble(row, cols.cost);
                // Cost is summed unconditionally, including tax, fees, credits and
                // refunds — that sum IS the invoice figure, and filtering any line
                // item type here is what makes a total stop matching the bill.
                acc.total += cost;
                acc.rows++;

                String date = cols.getDate(row, cols.usageStart);
                if (date != null) {
                    acc.daily.merge(date, cost, Double::sum);
                }

                String service = cols.getService(row);
                String region = cols.getRegion(row);
                acc.svcRegion.merge(service + '\u0000' + region, cost, Double::sum);

                double qty = cols.getDouble(row, cols.usageAmount);
                if (isBillableRow(cost, qty)) {
                    String usageType = cols.getString(row, cols.usageType);
                    if (usageType.isEmpty()) usageType = "Other";
                    String uKey = service + '\u0000' + region + '\u0000' + usageType;
                    acc.svcUsageCost.compute(uKey, (k, v) -> v == null
                            ? new double[]{cost, qty}
                            : new double[]{v[0] + cost, v[1] + qty});
                    String unit = cols.getString(row, cols.pricingUnit);
                    if (!unit.isEmpty()) {
                        acc.svcUsageUnit.putIfAbsent(uKey, unit);
                    }
                }
            }
        }
    }

    /** Running totals across every file in one billing period. */
    static final class Accumulator {
        double total = 0.0;
        long rows = 0;
        final Map<String, Double> daily = new LinkedHashMap<>();
        final Map<String, Double> svcRegion = new HashMap<>();
        final Map<String, double[]> svcUsageCost = new HashMap<>();   // key → [cost, quantity]
        final Map<String, String> svcUsageUnit = new HashMap<>();

        CurAggregates toAggregates() {
            CurAggregates agg = new CurAggregates();
            agg.setTotal(round(total));
            agg.setRowCount(rows);

            // Daily, ordered by date so the trend chart needs no client-side sort.
            List<CurAggregates.DailyEntry> dailyOut = new ArrayList<>();
            daily.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> dailyOut.add(new CurAggregates.DailyEntry(e.getKey(), round(e.getValue()))));
            agg.setDaily(dailyOut);

            List<CurAggregates.SvcRegionEntry> svcRegionOut = new ArrayList<>();
            svcRegion.forEach((k, v) -> {
                String[] parts = k.split("\u0000", -1);
                svcRegionOut.add(new CurAggregates.SvcRegionEntry(parts[0], parts[1], round(v)));
            });
            svcRegionOut.sort(Comparator.comparingDouble(CurAggregates.SvcRegionEntry::getCost).reversed());
            agg.setSvcRegion(svcRegionOut);

            List<CurAggregates.SvcUsageEntry> svcUsageOut = new ArrayList<>();
            svcUsageCost.forEach((k, v) -> {
                String[] parts = k.split("\u0000", -1);
                svcUsageOut.add(new CurAggregates.SvcUsageEntry(
                        parts[0], parts[1], parts[2], round(v[0]), round(v[1]),
                        svcUsageUnit.getOrDefault(k, "")));
            });
            svcUsageOut.sort(Comparator.comparingDouble(CurAggregates.SvcUsageEntry::getCost).reversed());
            agg.setSvcUsage(svcUsageOut);

            return agg;
        }
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    /**
     * Resolves CUR column positions by name, tolerating both schema dialects.
     *
     * CUR 2.0 uses snake_case ({@code line_item_unblended_cost}) while legacy CUR
     * uses slash-qualified camelCase ({@code lineItem/UnblendedCost}). Supporting
     * both means a customer who already had a legacy export configured before this
     * feature shipped is readable without re-onboarding.
     */
    private static final class ColumnIndex {
        private final Map<String, Integer> byName = new HashMap<>();

        final int cost;
        final int usageStart;
        final int usageType;
        final int usageAmount;
        final int pricingUnit;
        final int productName;
        final int productCode;
        final int regionCode;

        ColumnIndex(String[] header) {
            for (int i = 0; i < header.length; i++) {
                if (header[i] != null) {
                    byName.put(header[i].trim().toLowerCase(), i);
                }
            }
            cost        = find("line_item_unblended_cost", "lineitem/unblendedcost");
            usageStart  = find("line_item_usage_start_date", "lineitem/usagestartdate");
            usageType   = find("line_item_usage_type", "lineitem/usagetype");
            usageAmount = find("line_item_usage_amount", "lineitem/usageamount");
            pricingUnit = find("pricing_unit", "pricing/unit");
            productName = find("product_product_name", "product/productname");
            productCode = find("line_item_product_code", "lineitem/productcode");
            regionCode  = find("product_region_code", "product/region", "product_region");
        }

        private int find(String... names) {
            for (String n : names) {
                Integer idx = byName.get(n);
                if (idx != null) return idx;
            }
            return -1;
        }

        /** Without a cost column the file tells us nothing; the caller skips it. */
        boolean isUsable() {
            return cost >= 0;
        }

        String getString(String[] row, int idx) {
            if (idx < 0 || idx >= row.length || row[idx] == null) return "";
            return row[idx].trim();
        }

        double getDouble(String[] row, int idx) {
            String s = getString(row, idx);
            if (s.isEmpty()) return 0.0;
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        /** CUR timestamps are ISO-8601; only the date part is needed for daily rollup. */
        String getDate(String[] row, int idx) {
            String s = getString(row, idx);
            if (s.length() >= 10) return s.substring(0, 10);
            return s.isEmpty() ? null : s;
        }

        /**
         * Human-readable service name, falling back to the product code.
         * CUR leaves product_product_name empty on some line item types (notably
         * tax and Savings Plan rows) where the code is still present.
         */
        String getService(String[] row) {
            String name = getString(row, productName);
            if (!name.isEmpty()) return normaliseServiceName(name);
            String code = getString(row, productCode);
            return code.isEmpty() ? "Other" : normaliseServiceName(code);
        }

        /** Global (non-regional) charges carry no region code. */
        String getRegion(String[] row) {
            String r = getString(row, regionCode);
            return r.isEmpty() ? "global" : r;
        }
    }

    /**
     * Maps CUR product codes onto the display names the invoice logic keys off.
     *
     * This is not cosmetic. InvoiceManagementService splits invoice line items into a
     * separate CloudFront row, and applies per-service discounts, by exact-matching
     * the service name against "Amazon CloudFront". CUR populates
     * product_product_name as "Amazon CloudFront" but leaves it empty on some line
     * item types, where the fallback product code is "AmazonCloudFront" — no space.
     * Without this mapping those rows would silently land in the standard
     * consumption group and be discounted at the wrong rate, which is a billing
     * error rather than a display glitch.
     *
     * Extend this when another service gains its own invoice row or discount rate.
     */
    static String normaliseServiceName(String raw) {
        if (raw == null || raw.isBlank()) return "Other";
        String s = raw.trim();
        switch (s) {
            case "AmazonCloudFront":
                return "Amazon CloudFront";
            case "AmazonEC2":
                return "Amazon Elastic Compute Cloud";
            case "AmazonS3":
                return "Amazon Simple Storage Service";
            case "AmazonRDS":
                return "Amazon Relational Database Service";
            case "AWSDataTransfer":
                return "AWS Data Transfer";
            default:
                return s;
        }
    }

    // ------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------

    /**
     * Writes the four data keys, then the sentinel.
     *
     * Order matters: AWS_CUR_STATE is what hasCurData() checks, so writing it last
     * guarantees a month is either fully readable or treated as absent. A crash
     * mid-write leaves stale-but-complete previous data or nothing, never a mix.
     */
    private void persistAll(String accountId, YearMonth ym, CurAggregates agg) {
        persist(keyTotal(accountId, ym), agg.getTotal());
        persist(keyDaily(accountId, ym), agg.getDaily());
        persist(keySvcRegion(accountId, ym), agg.getSvcRegion());
        persist(keySvcUsage(accountId, ym), agg.getSvcUsage());

        CurIngestState state = new CurIngestState();
        state.setAssemblyId(agg.getAssemblyId());
        state.setBillingPeriod(agg.getBillingPeriod());
        state.setRowCount(agg.getRowCount());
        state.setTotal(agg.getTotal());
        state.setIngestedAt(LocalDateTime.now().toString());
        persist(keyState(accountId, ym), state);
    }

    private void persist(String key, Object value) {
        try {
            CachedData row = cachedDataRepository.findById(key).orElse(new CachedData());
            row.setCacheKey(key);
            row.setJsonData(objectMapper.writeValueAsString(value));
            row.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(row);
        } catch (Exception e) {
            logger.warn("[CUR] Failed to persist {}: {}", key, e.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // Inner types
    // ------------------------------------------------------------------------

    private static final class ManifestInfo {
        final List<String> dataFileKeys;
        final String assemblyId;

        ManifestInfo(List<String> dataFileKeys, String assemblyId) {
            this.dataFileKeys = dataFileKeys;
            this.assemblyId = assemblyId;
        }
    }

    /** Ingest metadata, and the completeness sentinel for a month. */
    public static class CurIngestState {
        private String assemblyId;
        private String billingPeriod;
        private long rowCount;
        private double total;
        private String ingestedAt;

        public String getAssemblyId() { return assemblyId; }
        public void setAssemblyId(String assemblyId) { this.assemblyId = assemblyId; }
        public String getBillingPeriod() { return billingPeriod; }
        public void setBillingPeriod(String billingPeriod) { this.billingPeriod = billingPeriod; }
        public long getRowCount() { return rowCount; }
        public void setRowCount(long rowCount) { this.rowCount = rowCount; }
        public double getTotal() { return total; }
        public void setTotal(double total) { this.total = total; }
        public String getIngestedAt() { return ingestedAt; }
        public void setIngestedAt(String ingestedAt) { this.ingestedAt = ingestedAt; }
    }
}
