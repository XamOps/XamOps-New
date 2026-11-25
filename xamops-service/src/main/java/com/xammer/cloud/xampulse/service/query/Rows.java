package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.dto.XpLogRow;
import com.xammer.cloud.xampulse.dto.XpSeverity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Null-safe accessors for JDBC result maps and the ClickHouse log row mapper. */
public final class Rows {

    private Rows() {}

    public static long l(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public static double d(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    public static Double dOrNull(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v instanceof Number n) {
            double x = n.doubleValue();
            return Double.isNaN(x) || Double.isInfinite(x) ? null : x;
        }
        return null;
    }

    public static String s(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public static Map<String, String> stringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        return out;
    }

    public static XpLogRow logRow(Map<String, Object> row) {
        long tenant = l(row, "tenant_id");
        long tsMs = l(row, "ts_ms");
        String instance = s(row, "instance_id");
        String sev = row.containsKey("severity_name") ? s(row, "severity_name") : s(row, "severity"); // archive rows keep "severity"
        XpSeverity severity;
        try {
            severity = XpSeverity.valueOf(sev);
        } catch (IllegalArgumentException e) {
            severity = XpSeverity.INFO;
        }
        Map<String, String> attrs = stringMap(row.get("attrs"));
        String trace = s(row, "trace_id");
        String container = s(row, "container_id");
        return new XpLogRow(LogRowIdCodec.encode(tenant, tsMs, instance, s(row, "body_hash")), Instant.ofEpochMilli(tsMs),
                tenant, s(row, "region"), instance, s(row, "service"), s(row, "source"), severity, s(row, "body"),
                attrs, trace.isEmpty() ? null : trace, container.isEmpty() ? null : container);
    }
}
