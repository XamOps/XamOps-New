package com.xammer.cloud.service.cur;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CurSourceRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Discovers what a CUR actually contains by reading its {@code Manifest.json},
 * and marks the source usable or not.
 *
 * <p>Everything here is read from the report rather than assumed, because for
 * {@link com.xammer.cloud.domain.AwsBillingSource#EXISTING_CUR} the report
 * belongs to the customer: it may be monthly CSV without resource IDs, and a
 * feature that assumed otherwise would fail at query time with a missing-column
 * error instead of being hidden.
 *
 * <p>Deliberately manifest-based rather than calling
 * {@code cur:DescribeReportDefinitions}. That API only describes reports defined
 * in the account being called, which covers the reports XamOps creates but not a
 * customer's existing one delivered from a different account — and it would add
 * an SDK dependency to learn less than the manifest already states.
 */
@Service
public class CurSourceValidationService {

    private static final Logger logger = LoggerFactory.getLogger(CurSourceValidationService.class);

    /** Billing-period folder names look like 20260801-20260901. */
    private static final DateTimeFormatter PERIOD_FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CurSourceRepository curSourceRepository;
    private final AwsClientProvider awsClientProvider;
    private final CurAthenaQueryService athena;
    private final CurTableProvisioningService tableProvisioningService;
    private final ObjectMapper objectMapper;

    public CurSourceValidationService(CurSourceRepository curSourceRepository,
                                      AwsClientProvider awsClientProvider,
                                      CurAthenaQueryService athena,
                                      CurTableProvisioningService tableProvisioningService,
                                      ObjectMapper objectMapper) {
        this.curSourceRepository = curSourceRepository;
        this.awsClientProvider = awsClientProvider;
        this.athena = athena;
        this.tableProvisioningService = tableProvisioningService;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads the report's newest manifest, records what it found, and sets the
     * source ACTIVE or FAILED.
     *
     * <p>Deliberately not {@code @Transactional}: this method spends most of its
     * time in S3 and Athena calls, and an Athena query is allowed up to 60s. Holding
     * a pooled DB connection open across that would tie connection-pool pressure to
     * AWS latency. The individual {@code save} calls are transactional on their own,
     * which is all the atomicity a single-row status update needs.
     *
     * @param account an account under this source, needed only to assume a role for
     *                a customer-owned bucket; ignored when the source is XamOps-owned
     */
    public CurSource validate(CurSource source, CloudAccount account) {
        try {
            S3Client s3 = resolveS3(source, account);

            List<String> periodFolders = listBillingPeriodFolders(s3, source);
            if (periodFolders.isEmpty()) {
                return markFailed(source, "No billing period folders found under "
                        + "s3://" + source.getS3Bucket() + "/" + reportRoot(source));
            }

            // Oldest folder is how far back the report goes. A legacy CUR does not
            // backfill, so this is normally the month it was created — and it is what
            // stops a query for older history returning a short sum.
            source.setEarliestBillingPeriod(toYearMonth(periodFolders.get(0)));

            String newestFolder = periodFolders.get(periodFolders.size() - 1);
            Optional<JsonNode> manifest = readManifest(s3, source, newestFolder);
            if (manifest.isEmpty()) {
                return markFailed(source, "No readable Manifest.json in " + newestFolder);
            }

            applyManifest(source, manifest.get());

            // Recreate the table from the manifest we just read, before anything queries
            // it. A CUR gains columns over time — AWS adds product attributes, the
            // customer activates cost allocation tags — and a table built from an older
            // manifest is missing exactly the columns a new feature would ask for.
            tableProvisioningService.provision(source, account, manifest.get(), periodFolders);

            // Granularity is the one thing the manifest does not state, and it decides
            // whether daily charts can be served at all. Probing the data is cheap
            // (one partition, one column) and works the same for a report we created
            // and one we were handed. Runs after provisioning because it queries the table.
            source.setTimeUnit(probeTimeUnit(source, account, YearMonth.parse(toYearMonth(newestFolder))));

            source.setStatus("ACTIVE");
            source.setStatusDetail(null);
            source.setLastValidatedAt(LocalDateTime.now());
            logger.info("✅ [CUR] Source {} validated: {} granularity, format={}, resourceIds={}, from {}",
                    source.getId(), source.getTimeUnit(), source.getFormat(),
                    source.getResourceIdsEnabled(), source.getEarliestBillingPeriod());
            return curSourceRepository.save(source);

        } catch (Exception e) {
            logger.error("[CUR] Validation failed for source {}: {}", source.getId(), e.getMessage(), e);
            return markFailed(source, e.getMessage());
        }
    }

    /**
     * Picks the credentials for reading this source's manifests. Mirrors
     * {@code CurAthenaQueryService.resolveClient} — a second XamOps MPA is a
     * separate AWS account and needs its role assumed, so ownership alone does not
     * imply host credentials.
     */
    private S3Client resolveS3(CurSource source, CloudAccount account) {
        if (source.isXamopsOwned()) {
            if (source.getAssumeRoleArn() != null && !source.getAssumeRoleArn().isBlank()) {
                return awsClientProvider.getRoleS3Client(
                        source.getAssumeRoleArn(), source.getAssumeRoleExternalId(), source.getRegion());
            }
            return awsClientProvider.getHostS3Client(source.getRegion());
        }
        if (account == null) {
            throw new IllegalStateException(
                    "CUR source " + source.getId() + " is customer-owned but no account was supplied to assume");
        }
        return awsClientProvider.getS3Client(account, source.getRegion());
    }

    /** {@code <prefix>/<reportName>/} — where CUR writes one folder per billing period. */
    private String reportRoot(CurSource source) {
        String prefix = source.getS3Prefix();
        if (prefix == null || prefix.isBlank()) {
            return source.getReportName() + "/";
        }
        return prefix.replaceAll("^/+|/+$", "") + "/" + source.getReportName() + "/";
    }

    /**
     * Billing-period folders, oldest first. Names that are not a period range are
     * skipped — the report root also holds the Parquet data directory in some
     * layouts, which is not a period.
     */
    private List<String> listBillingPeriodFolders(S3Client s3, CurSource source) {
        List<String> folders = new ArrayList<>();
        String continuationToken = null;

        do {
            ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(source.getS3Bucket())
                    .prefix(reportRoot(source))
                    .delimiter("/")
                    .continuationToken(continuationToken)
                    .build());

            for (CommonPrefix cp : response.commonPrefixes()) {
                String name = cp.prefix()
                        .substring(reportRoot(source).length())
                        .replaceAll("/+$", "");
                if (name.matches("\\d{8}-\\d{8}")) {
                    folders.add(name);
                }
            }
            continuationToken = response.nextContinuationToken();
        } while (continuationToken != null);

        folders.sort(String::compareTo);
        return folders;
    }

    private Optional<JsonNode> readManifest(S3Client s3, CurSource source, String periodFolder) {
        String key = reportRoot(source) + periodFolder + "/" + source.getReportName() + "-Manifest.json";
        try {
            byte[] bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(source.getS3Bucket())
                    .key(key)
                    .build()).asByteArray();
            return Optional.of(objectMapper.readTree(bytes));
        } catch (Exception e) {
            logger.warn("[CUR] Could not read manifest {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Records format and which optional column families the report carries.
     *
     * <p>Column presence is what gates features: resource-level views need
     * {@code lineItem/ResourceId}, which only exists when the report was created
     * with RESOURCES, and container cost allocation needs {@code splitLineItem/*}.
     */
    private void applyManifest(CurSource source, JsonNode manifest) {
        String contentType = manifest.path("contentType").asText("");
        String compression = manifest.path("compression").asText("");
        source.setFormat(contentType.contains("parquet") || "Parquet".equalsIgnoreCase(compression)
                ? "Parquet"
                : "csv");

        boolean hasResourceId = false;
        boolean hasSplitCost = false;
        boolean prefixed = CurTableProvisioningService.usesPrefixedNames(manifest);

        for (JsonNode column : manifest.path("columns")) {
            String category = column.path("category").asText("");
            String name = column.path("name").asText("");
            if (category.isBlank() || name.isBlank()) {
                continue;
            }

            // Compare the RESOLVED Athena column name, not the raw manifest name.
            //
            // AWS ships two manifest dialects — {"lineItem","ResourceId"} and
            // {"lineItem","line_item_resource_id"} — and matching the raw name against
            // "ResourceId" silently missed the second. That reported resourceIdsEnabled
            // false on a report that has the column, quietly disabling every
            // resource-level feature with nothing in the logs to explain it. Resolving
            // first means both dialects hit the same branch.
            String athenaName = CurTableProvisioningService.toAthenaColumn(category, name, prefixed);
            if ("line_item_resource_id".equals(athenaName)) {
                hasResourceId = true;
            }
            if (athenaName.startsWith("split_line_item_")) {
                hasSplitCost = true;
            }
        }

        source.setResourceIdsEnabled(hasResourceId);
        source.setSplitCostDataEnabled(hasSplitCost);
    }

    /**
     * Determines the report's time unit from the ratio of distinct usage
     * timestamps to distinct usage days in the newest billing period.
     *
     * <p>More than one timestamp per day means HOURLY; exactly one across several
     * days means DAILY; a single bucket for the whole period means MONTHLY. The
     * ratio is used rather than a raw count because a raw count cannot separate an
     * hourly report on its first day (24 rows) from a daily report mid-month (also
     * ~24) — the failure that would silently disable every daily chart on a
     * freshly created hourly report.
     *
     * <p>Scoped to one partition so the scan stays trivial; this is the only place
     * a query runs without an account filter, and it reads no cost column.
     *
     * <p>Falls back to whatever is already recorded on failure rather than
     * overwriting a known-good value with a guess.
     */
    private String probeTimeUnit(CurSource source, CloudAccount account, YearMonth period) {
        String tsExpr = CurSql.usageDateExpr(source);
        String sql = "SELECT COUNT(DISTINCT " + tsExpr + ") AS ts_count,"
                + " COUNT(DISTINCT date_format(" + tsExpr + ", '%Y-%m-%d')) AS day_count"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + CurSql.partitionPredicate(source, period, period);
        try {
            var rows = athena.query(source, account, sql);
            if (rows.isEmpty()) {
                return source.getTimeUnit();
            }
            long timestamps = Long.parseLong(rows.get(0).getOrDefault("ts_count", "0"));
            long days = Long.parseLong(rows.get(0).getOrDefault("day_count", "0"));

            if (days <= 1 && timestamps <= 1) {
                return "MONTHLY";
            }
            return timestamps > days ? "HOURLY" : "DAILY";
        } catch (Exception e) {
            logger.warn("[CUR] Could not probe time unit for source {} ({}); keeping {}",
                    source.getId(), e.getMessage(), source.getTimeUnit());
            return source.getTimeUnit();
        }
    }

    private String toYearMonth(String periodFolder) {
        // "20260801-20260901" -> "2026-08"
        String start = periodFolder.substring(0, 8);
        return YearMonth.from(java.time.LocalDate.parse(start, PERIOD_FOLDER)).toString();
    }

    private CurSource markFailed(CurSource source, String detail) {
        source.setStatus("FAILED");
        source.setStatusDetail(detail != null && detail.length() > 512 ? detail.substring(0, 512) : detail);
        source.setLastValidatedAt(LocalDateTime.now());
        return curSourceRepository.save(source);
    }
}
