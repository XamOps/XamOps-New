package com.xammer.cloud.xampulse.service.query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A host's total physical memory, worked out from what the agent already sends: bytes used divided by the used
 * fraction (system.memory.usage / system.memory.utilization, state=used). Used to recognise containers with no
 * memory limit: Docker then reports the whole host's memory as the "limit".
 */
final class HostMemory {

    private HostMemory() {}

    /** Share of host memory at or above which a container's reported limit counts as "no limit set". */
    static final double NO_LIMIT_SHARE = 0.97;

    /** tenant/instance -> total bytes, for the instances in {@code scope} that reported both values since {@code since}. */
    static Map<String, Double> totals(ClickHouseExecutor executor, TenantScopedQueryBuilder builder, QueryScope scope, long sinceEpochSec) {
        Map<String, Double> used = new HashMap<>();
        Map<String, Double> share = new HashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.latestMetrics(s, sinceEpochSec,
                List.of("system.memory.usage", "system.memory.utilization")))) {
            for (Map<String, Object> r : tr.rows()) {
                if (!"state=used".equals(Rows.s(r, "labels_key"))) {
                    continue;
                }
                Double v = Rows.dOrNull(r, "v");
                String key = tr.tenantId() + "/" + Rows.s(r, "instance_id");
                if (v != null) {
                    ("system.memory.usage".equals(Rows.s(r, "metric")) ? used : share).put(key, v);
                }
            }
        }
        Map<String, Double> out = new HashMap<>();
        used.forEach((k, u) -> {
            Double f = share.get(k);
            if (f != null && f > 0.001) {
                out.put(k, u / f);
            }
        });
        return out;
    }

    static Boolean isHostLimit(Double limitBytes, Double hostBytes) {
        if (limitBytes == null || hostBytes == null) {
            return null;
        }
        return limitBytes >= hostBytes * NO_LIMIT_SHARE;
    }
}
