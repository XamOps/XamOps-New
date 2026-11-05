package com.xammer.cloud.xampulse.service.query;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** One metric series request, validated at construction so the builder only sees safe shapes. */
public record MetricSpec(String metric, String agg, List<String> groupBy, List<LabelFilter> filters) {

    public static final Set<String> AGGS = Set.of("avg", "min", "max", "sum", "p50", "p95", "p99", "rate", "last");
    public static final Pattern METRIC_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,128}$");
    public static final Pattern LABEL_KEY = Pattern.compile("^[A-Za-z0-9_.\\-]{1,64}$");
    public static final int MAX_FILTER_VALUE = 200;

    public record LabelFilter(String key, String op, String value) {
        public LabelFilter {
            if (key == null || !LABEL_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid label key");
            }
            if (!Set.of("=", "!=", "=~").contains(op)) {
                throw new IllegalArgumentException("Invalid label operator");
            }
            if (value == null || value.length() > MAX_FILTER_VALUE) {
                throw new IllegalArgumentException("Invalid label value");
            }
        }
    }

    public MetricSpec {
        if (metric == null || !METRIC_NAME.matcher(metric).matches()) {
            throw new IllegalArgumentException("Invalid metric name");
        }
        agg = agg == null ? "" : agg.toLowerCase(Locale.ROOT);
        if (!AGGS.contains(agg)) {
            throw new IllegalArgumentException("Unsupported aggregation");
        }
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        if (groupBy.size() > 3) {
            throw new IllegalArgumentException("At most 3 group-by keys");
        }
        for (String g : groupBy) {
            if (!"instance_id".equals(g) && !LABEL_KEY.matcher(g).matches()) {
                throw new IllegalArgumentException("Invalid group-by key");
            }
            if ("region".equals(g)) {
                throw new IllegalArgumentException("Grouping metrics by region is not supported");
            }
        }
        filters = filters == null ? List.of() : List.copyOf(filters);
        if (filters.size() > 10) {
            throw new IllegalArgumentException("At most 10 filters");
        }
    }
}
