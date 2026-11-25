package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ClickHouse HTTP interface client. The tenant, region and hour are bound with native query parameters
 * ({@code param_*}), never concatenated, the tenant row-policy setting is sent too, and the columns are an explicit
 * list of landed (already redacted) fields. S3 credentials never reach ClickHouse: this class only reads.
 * It uses the READER account, so the row policy applies.
 *
 * <p>NOT exercised against a real ClickHouse in this repository's tests.
 */
@Component("xpClickHouseHttpLogHourExporter")
@ConditionalOnProperty(name = "xampulse.archive.enabled", havingValue = "true")
public class ClickHouseHttpLogHourExporter implements LogHourExporter {

    static final String COLUMNS = "ts, ingest_ts, region, instance_id, service, source, container_id, k8s_ns, k8s_pod, "
            + "toString(severity) AS severity, body, trace_id, span_id, attrs";
    static final String EXPORT_SQL = "SELECT " + COLUMNS + " FROM xp_logs WHERE tenant_id IN ({tenant:UInt64}) AND region = {region:String} "
            + "AND ts >= fromUnixTimestamp64Milli({from:Int64}) AND ts < fromUnixTimestamp64Milli({to:Int64}) ORDER BY ts FORMAT Parquet";
    static final String STATS_SQL = "SELECT count() AS c, toUnixTimestamp64Milli(min(ts)) AS lo, toUnixTimestamp64Milli(max(ts)) AS hi FROM xp_logs "
            + "WHERE tenant_id IN ({tenant:UInt64}) AND region = {region:String} "
            + "AND ts >= fromUnixTimestamp64Milli({from:Int64}) AND ts < fromUnixTimestamp64Milli({to:Int64}) FORMAT TabSeparated";

    private final XamPulseProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ClickHouseHttpLogHourExporter(XamPulseProperties props) {
        this.props = props;
    }

    @Override
    public Exported export(long tenantId, String region, Instant hourStart, Path target) throws IOException {
        String base = props.getArchive().getClickhouseHttpUrl();
        if (base.isBlank()) {
            throw new IOException("xampulse.archive.clickhouse-http-url is not set");
        }
        long from = hourStart.toEpochMilli();
        long to = hourStart.plus(Duration.ofHours(1)).toEpochMilli();
        String stats = send(base, STATS_SQL, tenantId, region, from, to, false, null);
        String[] f = stats.trim().split("\t");
        long rows = f.length == 3 ? parse(f[0]) : 0;
        if (rows == 0) {
            return null;
        }
        send(base, EXPORT_SQL, tenantId, region, from, to, true, target);
        return new Exported(rows, parse(f[1]), parse(f[2]));
    }

    static String url(String base, String sql, long tenantId, String region, long from, long to, boolean parquet) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("database", "");
        q.put("query", sql);
        q.put("param_tenant", Long.toString(tenantId));
        q.put("param_region", region);
        q.put("param_from", Long.toString(from));
        q.put("param_to", Long.toString(to));
        q.put("SQL_xp_tenant", Long.toString(tenantId));
        q.put("max_execution_time", "300");
        if (parquet) {
            q.put("output_format_parquet_compression_method", "zstd");
        }
        StringBuilder sb = new StringBuilder(base.endsWith("/") ? base : base + "/").append('?');
        boolean first = true;
        for (var e : q.entrySet()) {
            if (e.getKey().equals("database")) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String send(String base, String sql, long tenantId, String region, long from, long to, boolean parquet, Path target) throws IOException {
        XamPulseProperties.ClickHouse ch = props.getClickhouse();
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url(base, sql, tenantId, region, from, to, parquet)))
                .timeout(Duration.ofMinutes(10)).POST(HttpRequest.BodyPublishers.noBody())
                .header("X-ClickHouse-Database", ch.getDatabase());
        if (!ch.getReaderUser().isBlank()) {
            String basic = Base64.getEncoder().encodeToString((ch.getReaderUser() + ":" + ch.getReaderPassword()).getBytes(StandardCharsets.UTF_8));
            rb.header("Authorization", "Basic " + basic);
        }
        try {
            if (target == null) {
                HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    throw new IOException("ClickHouse returned HTTP " + r.statusCode());
                }
                return r.body();
            }
            HttpResponse<Path> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofFile(target));
            if (r.statusCode() != 200) {
                throw new IOException("ClickHouse returned HTTP " + r.statusCode());
            }
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
