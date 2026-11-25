package com.xammer.cloud.xampulse.service.archive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpLogRow;
import com.xammer.cloud.xampulse.service.query.FilterAst;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.ScopedSql;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Reads archived Parquet objects through ClickHouse's {@code s3()} table function over the HTTP interface, one object
 * per statement. It reuses the log filter compiler of {@link TenantScopedQueryBuilder}, so a filter means exactly what
 * it means on the hot store.
 *
 * <p>Safety: every value (tenant, URL, credentials and each filter value) travels as a native {@code param_*} query
 * parameter, never as SQL text. The tenant is a server-side value and is also compared in the WHERE clause, because the
 * Parquet files carry no tenant column and the row policy does not apply to {@code s3()}. Keys come only from the
 * caller-supplied manifest entries and must sit under {@code xamops-xampulse/v1/logs/account=<aws account>/}.
 *
 * <p>NOT exercised against a real ClickHouse or S3 in this repository's tests; only the generated SQL and URL are
 * unit-tested. The role credentials are short-lived and are sent to the ClickHouse server in the request, which
 * therefore must be on a trusted network; they are never logged by this class.
 */
@Component("xpClickHouseS3ArchiveSearchExecutor")
@ConditionalOnProperty(name = {"xampulse.enabled", "xampulse.archive.enabled"}, havingValue = "true")
public class ClickHouseS3ArchiveSearchExecutor implements ArchiveSearchExecutor {

    static final String STRUCTURE = "ts DateTime64(3, ''UTC''), ingest_ts DateTime64(3, ''UTC''), region String, "
            + "instance_id String, service String, source String, container_id String, k8s_ns String, k8s_pod String, "
            + "severity String, body String, trace_id String, span_id String, attrs Map(String, String)";
    private static final String SEVERITY_ENUM = "Enum8(''TRACE'' = 1, ''DEBUG'' = 2, ''INFO'' = 3, ''WARN'' = 4, ''ERROR'' = 5, ''FATAL'' = 6)";
    private static final Pattern KEY_CHARS = Pattern.compile("^[A-Za-z0-9/=_.\\-]{1,512}$");
    private static final Pattern BUCKET = Pattern.compile("^[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9]$");
    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-[0-9]$");

    /** SQL text and the native parameters that go with it. Package-visible for tests. */
    record Statement(String sql, Map<String, String> params) {}

    private final XamPulseProperties props;
    private final ArchiveRoleCredentialProvider credentials;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public ClickHouseS3ArchiveSearchExecutor(XamPulseProperties props, ArchiveRoleCredentialProvider credentials, ObjectMapper objectMapper) {
        this.props = props;
        this.credentials = credentials;
        this.mapper = objectMapper;
    }

    @Override
    public Result search(XpAccountConfig cfg, String awsAccountId, QueryScope scope, List<ArchiveManifest.Entry> files,
                         FilterAst filter, int rowLimit, ProgressSink progress, BooleanSupplier cancelled) {
        String base = props.getArchive().getClickhouseHttpUrl();
        if (base.isBlank()) {
            throw new Failure(Failure.INTERNAL);
        }
        List<XpLogRow> all = new ArrayList<>();
        boolean truncated = false;
        int done = 0;
        ArchiveRoleCredentialProvider.RoleCredentials creds;
        try {
            creds = credentials.credentials(cfg);
        } catch (RuntimeException e) {
            throw new Failure(Failure.ACCESS_DENIED);
        }
        for (ArchiveManifest.Entry file : files) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            Statement st = statement(cfg, awsAccountId, scope, file, filter, rowLimit, creds);
            for (XpLogRow row : run(base, st, scope.accountIds().get(0))) {
                all.add(row);
            }
            all.sort(Comparator.comparing(XpLogRow::ts).reversed());
            if (all.size() > rowLimit) {
                all = new ArrayList<>(all.subList(0, rowLimit));
                truncated = true;
            }
            progress.filesDone(++done);
        }
        return new Result(List.copyOf(all), truncated);
    }

    /** Builds the statement for one object. Package-visible so the SQL can be asserted without a server. */
    Statement statement(XpAccountConfig cfg, String awsAccountId, QueryScope scope, ArchiveManifest.Entry file,
                        FilterAst filter, int rowLimit, ArchiveRoleCredentialProvider.RoleCredentials creds) {
        if (scope.accountIds().size() != 1) {
            throw new IllegalArgumentException("an archive search targets exactly one tenant");
        }
        String url = objectUrl(cfg, awsAccountId, file.key());
        long tenant = scope.accountIds().get(0);
        ScopedSql q = builder.search(scope, filter, true, rowLimit, null);
        String from = "FROM (SELECT toUInt64({xp_tenant:UInt64}) AS tenant_id, ts, ingest_ts, region, instance_id, service, "
                + "source, container_id, k8s_ns, k8s_pod, CAST(severity, '" + SEVERITY_ENUM + "') AS severity, body, trace_id, "
                + "span_id, attrs FROM s3({xp_url:String}, {xp_ak:String}, {xp_sk:String}, {xp_token:String}, 'Parquet', '"
                + STRUCTURE + "')) AS xp_logs";
        String sql = q.sql().replace("FROM xp_logs", from);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("xp_tenant", Long.toString(tenant));
        params.put("xp_url", url);
        params.put("xp_ak", creds.accessKeyId());
        params.put("xp_sk", creds.secretAccessKey());
        params.put("xp_token", creds.sessionToken());
        StringBuilder out = new StringBuilder();
        int idx = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?') {
                Object p = q.params().get(idx);
                String name = "xp_p" + idx;
                boolean integral = p instanceof Long || p instanceof Integer;
                out.append('{').append(name).append(':').append(integral ? "Int64" : "String").append('}');
                params.put(name, String.valueOf(p));
                idx++;
            } else {
                out.append(c);
            }
        }
        if (idx != q.params().size()) {
            throw new IllegalStateException("placeholder count does not match parameter count");
        }
        return new Statement(out + " FORMAT JSONEachRow", params);
    }

    /** Only keys inside this tenant's own archive prefix, built from the manifest, ever become a URL. */
    static String objectUrl(XpAccountConfig cfg, String awsAccountId, String key) {
        String prefix = ArchiveKeyLayout.ROOT + "logs/account=" + awsAccountId + "/";
        if (key == null || !key.startsWith(prefix) || key.contains("..") || !KEY_CHARS.matcher(key).matches()) {
            throw new IllegalArgumentException("archive key is outside the tenant prefix");
        }
        String bucket = cfg.getBucketName();
        String region = cfg.getBucketRegion() == null || cfg.getBucketRegion().isBlank() ? cfg.getHomeRegion() : cfg.getBucketRegion();
        if (bucket == null || !BUCKET.matcher(bucket).matches() || region == null || !REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("archive bucket is not valid");
        }
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + key;
    }

    static String requestUrl(String base, Statement st, long tenant, int timeoutSec) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("query", st.sql());
        st.params().forEach((k, v) -> q.put("param_" + k, v));
        q.put("SQL_xp_tenant", Long.toString(tenant));
        q.put("max_execution_time", Integer.toString(timeoutSec));
        StringBuilder sb = new StringBuilder(base.endsWith("/") ? base : base + "/").append('?');
        boolean first = true;
        for (var e : q.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private List<XpLogRow> run(String base, Statement st, long tenant) {
        XamPulseProperties.ClickHouse ch = props.getClickhouse();
        int timeout = Math.max(10, props.getArchive().getSearchTimeoutSec());
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(requestUrl(base, st, tenant, timeout)))
                .timeout(Duration.ofSeconds(timeout + 10L)).POST(HttpRequest.BodyPublishers.noBody())
                .header("X-ClickHouse-Database", ch.getDatabase());
        if (!ch.getReaderUser().isBlank()) {
            String basic = Base64.getEncoder().encodeToString((ch.getReaderUser() + ":" + ch.getReaderPassword()).getBytes(StandardCharsets.UTF_8));
            rb.header("Authorization", "Basic " + basic);
        }
        try {
            HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new Failure(classify(r.statusCode(), r.body()));
            }
            List<XpLogRow> rows = new ArrayList<>();
            for (String line : r.body().split("\n")) {
                if (!line.isBlank()) {
                    rows.add(Rows.logRow(mapper.readValue(line, new TypeReference<Map<String, Object>>() {})));
                }
            }
            return rows;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(Failure.INTERNAL);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new Failure(Failure.TIMEOUT);
        } catch (IOException e) {
            throw new Failure(Failure.INTERNAL);
        }
    }

    /** Maps a ClickHouse HTTP failure to a stable code. The response text is inspected, never returned or logged. */
    static String classify(int status, String body) {
        String b = body == null ? "" : body.toLowerCase();
        if (b.contains("accessdenied") || b.contains("access denied") || status == 403) {
            return Failure.ACCESS_DENIED;
        }
        if (b.contains("nosuchkey") || b.contains("no such key") || b.contains("the specified key does not exist")) {
            return Failure.OBJECT_MISSING;
        }
        if (b.contains("timeout") || b.contains("timed out") || b.contains("code: 159")) {
            return Failure.TIMEOUT;
        }
        return Failure.INTERNAL;
    }
}
