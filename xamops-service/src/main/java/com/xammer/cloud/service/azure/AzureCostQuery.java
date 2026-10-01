package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One Cost Management query, and the reading of its answer.
 *
 * <p>Split out of {@link AzureCostQueryService} rather than added to it because the two ask
 * different questions of the same endpoint. That service asks one fixed question — six months of
 * daily spend grouped by meter category and location — to fill the dashboard caches, and it builds
 * its body with {@code String.format}. This class exists for {@link AzureCostInsightsService},
 * which needs a different grouping per section, a tag key that is customer-supplied text, and the
 * ability to find out what a subscription's offer actually supports. None of that survives string
 * concatenation: a tag key containing a quote would produce a malformed body, and Cost Management
 * answers a malformed body with a 400 that looks exactly like an unsupported dimension.
 *
 * <h2>Probe and degrade</h2>
 * Which dimensions and aggregations a scope will accept is not knowable in advance. It varies by
 * agreement type — EA, MCA and pay-as-you-go expose different dimensions — and Cost Management
 * signals an unsupported one with a 400 rather than by omitting the column. So a section states
 * what it would like as an ordered list of attempts, most detailed first, and takes the first one
 * that answers. {@link Result#groupedBy()} then says what was actually obtained, so a record can be
 * filled honestly instead of carrying a fabricated value for a column that was never returned.
 *
 * <h2>Rows are aggregated by dimension tuple, never read positionally</h2>
 * Column order is not part of the contract, and the existing dashboard query's positional defaults
 * are a latent bug — a reordered response would read a cost as a currency code. Everything here is
 * resolved by column name. Rows are then summed into their dimension tuple, which also makes the
 * granularity irrelevant: a daily response and an undated one collapse to the same answer, so the
 * granularity can be dropped to the cheapest one a scope accepts without the caller caring.
 */
final class AzureCostQuery {

    private static final Logger log = LoggerFactory.getLogger(AzureCostQuery.class);

    private static final String API_VERSION = "2023-03-01";

    /**
     * Page ceiling. Grouping by resource id multiplies the row count by the resource count, so this
     * is reachable on a large subscription in a way it never was for the dashboard's meter-category
     * query — which is why hitting it is logged as a truncation rather than passed over.
     */
    private static final int MAX_PAGES = 50;

    /** Aggregation output names, as they come back in the {@code columns} array. */
    static final String COST = "Cost";
    static final String COST_USD = "CostUSD";
    static final String QUANTITY = "UsageQuantity";

    private final AzureArmClient armClient;
    private final ObjectMapper mapper;

    AzureCostQuery(AzureArmClient armClient, ObjectMapper mapper) {
        this.armClient = armClient;
        this.mapper = mapper;
    }

    // ── Request ─────────────────────────────────────────────────────────────────

    /**
     * Response column names a dimension can come back under, beyond the requested name.
     *
     * <p>Cost Management does not guarantee that the column is named after the dimension you asked
     * for, and the names it uses are not consistent across scopes and API versions — the resource is
     * {@code ResourceId} in the query API and {@code instanceId}/{@code instanceName} in the
     * Consumption API that preceded it, and the resource group appears as both
     * {@code ResourceGroupName} and {@code ResourceGroup} in Microsoft's own samples.
     *
     * <p>This matters more than it looks. A grouping whose column is not found is dropped from
     * {@link Result#groupedBy()}, and a section built on it then reports itself unavailable — so a
     * naming difference presents to the customer as "your subscription does not support this", which
     * is a claim about their Azure agreement rather than about our column lookup.
     *
     * <p>The same defensive resolution the existing {@link AzureCostQueryService} already does for
     * {@code Cost}, {@code Currency} and {@code UsageDate}, for the same reason.
     */
    private static final Map<String, List<String>> COLUMN_ALIASES = Map.of(
            "ResourceId", List.of("ResourceId", "InstanceId", "InstanceName", "ResourceGuid"),
            "ResourceGroupName", List.of("ResourceGroupName", "ResourceGroup"),
            "ResourceGroup", List.of("ResourceGroup", "ResourceGroupName"),
            "MeterCategory", List.of("MeterCategory", "ServiceName"),
            "MeterSubCategory", List.of("MeterSubCategory", "ServiceTier"),
            "ResourceLocation", List.of("ResourceLocation", "Location"),
            "UnitOfMeasure", List.of("UnitOfMeasure", "UnitOfMeasureId"),
            "ChargeType", List.of("ChargeType", "ChargeTypeName"));

    /** One column to group by: a Cost Management dimension, or the values of one tag key. */
    record Grouping(String type, String name) {

        static Grouping dimension(String name) {
            return new Grouping("Dimension", name);
        }

        static Grouping tagKey(String key) {
            return new Grouping("TagKey", key);
        }

        /**
         * The column names the response might carry for this grouping, most likely first.
         *
         * <p>A tag grouping comes back in a column named {@code TagValue} rather than after the key
         * itself, so the two cases cannot share one lookup.
         */
        List<String> columnCandidates() {
            if ("TagKey".equals(type)) {
                return List.of("TagValue", "TagKey");
            }
            return COLUMN_ALIASES.getOrDefault(name, List.of(name));
        }

        /** The canonical name callers key their reads on, whichever alias the response used. */
        String columnName() {
            return "TagKey".equals(type) ? "TagValue" : name;
        }
    }

    /** What a section asks for, and what it will settle for. */
    record Request(LocalDate from,
                   LocalDate to,
                   /** {@code ActualCost} or {@code AmortizedCost}. */
                   String costType,
                   /** Aggregations wanted, most informative first; reduced on rejection. */
                   List<String> aggregations,
                   /** Grouping sets to try in order, most detailed first. */
                   List<List<Grouping>> groupingAttempts) {
    }

    /**
     * What came back: the rows keyed by their dimension values, and what was actually grouped.
     *
     * @param groupedBy the grouping set that answered, which may be shorter than the one asked for
     * @param metrics   the aggregations present in the response, which may likewise be fewer
     */
    record Result(List<Row> rows, List<Grouping> groupedBy, List<String> metrics, String currency) {

        static final Result EMPTY = new Result(List.of(), List.of(), List.of(), null);

        boolean isEmpty() {
            return rows.isEmpty();
        }

        /** Whether a dimension was obtained, so a caller knows if a blank means "none" or "unknown". */
        boolean has(String dimension) {
            return groupedBy.stream().anyMatch(g -> g.columnName().equalsIgnoreCase(dimension)
                    || g.name().equalsIgnoreCase(dimension));
        }

        boolean hasMetric(String metric) {
            return metrics.stream().anyMatch(m -> m.equalsIgnoreCase(metric));
        }
    }

    /** One aggregated line: the dimension values that identify it, and its measures. */
    record Row(Map<String, String> dimensions, Map<String, Double> measures) {

        String dimension(String name) {
            return dimensions.getOrDefault(name, "");
        }

        double measure(String name) {
            return measures.getOrDefault(name, 0.0);
        }
    }

    // ── Execution ───────────────────────────────────────────────────────────────

    /**
     * Granularities to try, in order.
     *
     * <p>Null means the field is omitted, which the REST contract allows and which aggregates the
     * whole period into one row per dimension tuple — by far the cheapest shape for every question
     * here. {@code "Daily"} follows it because that is the form already proven against a live
     * subscription in {@link AzureCostQueryService}, and an omitted-granularity body being rejected
     * outright would otherwise disable every section at once with no way to tell it apart from a
     * subscription that spent nothing.
     *
     * <p>Daily is the fallback rather than the default because it multiplies the response by the
     * number of days in the month, and the resource-grouped queries are already the ones closest to
     * the page ceiling.
     */
    private static final List<String> GRANULARITIES = java.util.Arrays.asList(null, "Daily");

    /**
     * Runs the first attempt the scope accepts.
     *
     * <p>An empty answer is treated as a reason to try the next attempt, not as a final answer,
     * because the two are indistinguishable from the response: Cost Management returns
     * {@code rows: []} both for a dimension it will not group by and for a subscription that really
     * spent nothing. Trying the shorter grouping costs one more call in the genuinely-empty case and
     * is the only thing that makes the detailed case work at all. The last attempt's empty result is
     * returned as the answer, so "no spend" still reaches the caller.
     *
     * <p>Ordered so the cheapest request that could work is tried first: granularity outermost — a
     * scope either accepts an aggregate query or it does not, and finding out once is enough — then
     * grouping, then aggregations.
     */
    Result run(String subscriptionId, String token, Request request) {
        List<List<String>> metricAttempts = reduceAggregations(request.aggregations());

        for (String granularity : GRANULARITIES) {
            for (List<Grouping> grouping : request.groupingAttempts()) {
                for (List<String> metrics : metricAttempts) {
                    Result result = attempt(subscriptionId, token, request, grouping, metrics,
                            granularity);
                    if (result != null && !result.isEmpty()) {
                        if (grouping.size() < request.groupingAttempts().get(0).size()) {
                            log.info("[Azure cost] Subscription {} answered with {} of the {} requested"
                                            + " dimensions — degraded to {}", subscriptionId,
                                    grouping.size(), request.groupingAttempts().get(0).size(),
                                    grouping.stream().map(Grouping::name).toList());
                        }
                        if (granularity != null) {
                            log.info("[Azure cost] Subscription {} required granularity={} —"
                                    + " aggregating {} rows in-process", subscriptionId, granularity,
                                    result.rows().size());
                        }
                        return result;
                    }
                }
            }
        }
        return Result.EMPTY;
    }

    /**
     * Aggregation sets to try: everything asked for, then progressively fewer, always keeping cost.
     *
     * <p>{@code UsageQuantity} is the one at risk — it is what the price-vs-volume split and the
     * invoice's quantity column need, and it is not available on every scope. Dropping it costs
     * those two a column; dropping cost would be pointless.
     */
    private static List<List<String>> reduceAggregations(List<String> wanted) {
        List<List<String>> attempts = new ArrayList<>();
        List<String> current = new ArrayList<>(wanted);
        attempts.add(List.copyOf(current));
        while (current.size() > 1) {
            current.remove(current.size() - 1);
            attempts.add(List.copyOf(current));
        }
        if (!attempts.get(attempts.size() - 1).equals(List.of(COST))) {
            attempts.add(List.of(COST));
        }
        return attempts;
    }

    /**
     * One call. Null when the scope rejected the shape; a Result — possibly empty — when it answered.
     *
     * <p>{@link AzureArmClient#post} returns empty for a rejected body and throws for an
     * authorization or throttling failure, and the difference matters: the first is a dimension this
     * scope does not have and the next attempt should run, the second is a fault the section must
     * report rather than paper over with a shorter grouping.
     */
    private Result attempt(String subscriptionId, String token, Request request,
                           List<Grouping> grouping, List<String> metrics, String granularity) {
        String url = AzureArmClient.ARM_BASE + "/subscriptions/" + subscriptionId
                + "/providers/Microsoft.CostManagement/query?api-version=" + API_VERSION;
        String body = body(request, grouping, metrics, granularity);

        List<JsonNode> rows = new ArrayList<>();
        JsonNode columns = null;
        String next = url;
        int pages = 0;

        while (next != null && !next.isBlank() && pages < MAX_PAGES) {
            Optional<String> response = armClient.post(next, token, body);
            if (response.isEmpty()) {
                // Rejected shape. Logged at debug because the ladder makes this an expected step,
                // not a failure — ARM has already logged the status itself.
                log.debug("[Azure cost] Subscription {} would not group by {} with {}",
                        subscriptionId, grouping.stream().map(Grouping::name).toList(), metrics);
                return null;
            }
            try {
                JsonNode properties = mapper.readTree(response.get()).path("properties");
                if (columns == null) {
                    columns = properties.path("columns");
                }
                properties.path("rows").forEach(rows::add);
                next = properties.path("nextLink").asText(null);
            } catch (Exception e) {
                log.warn("[Azure cost] Could not parse the Cost Management response for {}: {}",
                        subscriptionId, e.getMessage());
                return null;
            }
            pages++;
        }
        if (pages >= MAX_PAGES) {
            log.warn("[Azure cost] Query for {} hit the {}-page ceiling grouping by {} —"
                            + " the tail beyond it is missing", subscriptionId, MAX_PAGES,
                    grouping.stream().map(Grouping::name).toList());
        }

        return read(columns, rows, grouping, metrics);
    }

    private String body(Request request, List<Grouping> grouping, List<String> metrics,
                        String granularity) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", request.costType());
        root.put("timeframe", "Custom");

        ObjectNode period = root.putObject("timePeriod");
        period.put("from", request.from() + "T00:00:00Z");
        // Inclusive of the last day: Cost Management treats the bound as a timestamp, so midnight
        // on the end date would exclude everything billed on it — a whole day missing from every
        // month, and the last day of the month is where reservation and support charges land.
        period.put("to", request.to() + "T23:59:59Z");

        ObjectNode dataset = root.putObject("dataset");
        // Omitted by default: the totals for the period are what every section here wants, and daily
        // rows would multiply the response for an answer that is summed away. Rows are aggregated by
        // dimension tuple regardless, so a scope that insists on a granularity — the second rung of
        // the ladder in run() — still produces the same result.
        if (granularity != null) {
            dataset.put("granularity", granularity);
        }
        ObjectNode aggregation = dataset.putObject("aggregation");
        for (String metric : metrics) {
            aggregation.putObject("total" + metric).put("name", metric).put("function", "Sum");
        }

        ArrayNode groupings = dataset.putArray("grouping");
        for (Grouping g : grouping) {
            groupings.addObject().put("type", g.type()).put("name", g.name());
        }

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            // Only reachable if Jackson cannot serialise its own ObjectNode.
            throw new IllegalStateException("Could not build the Cost Management request body", e);
        }
    }

    // ── Response ────────────────────────────────────────────────────────────────

    /**
     * Folds the response into one row per dimension tuple.
     *
     * <p>Column indexes come from the response's own {@code columns} array. A column that is asked
     * for and not returned is left out of {@link Result#metrics()} and
     * {@link Result#groupedBy()} rather than defaulted, so the caller can tell "this subscription
     * reported zero" from "this subscription was never asked".
     */
    private Result read(JsonNode columns, List<JsonNode> rawRows, List<Grouping> grouping,
                        List<String> metrics) {
        if (columns == null || !columns.isArray()) {
            return Result.EMPTY;
        }

        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            index.put(columns.get(i).path("name").asText().toLowerCase(Locale.ROOT), i);
        }

        // Which column each grouping actually landed in, keyed on the canonical name so callers read
        // by the dimension they asked for regardless of which alias the response used.
        Map<String, Integer> groupingColumns = new LinkedHashMap<>();
        List<Grouping> present = new ArrayList<>();
        for (Grouping g : grouping) {
            Integer at = firstIndex(index, g.columnCandidates().stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .toArray(String[]::new));
            if (at != null) {
                groupingColumns.put(g.columnName(), at);
                present.add(g);
            }
        }
        List<String> presentMetrics = metrics.stream()
                .filter(m -> index.containsKey(m.toLowerCase(Locale.ROOT)))
                .toList();

        if (present.size() < grouping.size()) {
            // Worth a line: this is the difference between "the subscription has no such spend" and
            // "the response named a column something we did not look for", and the two are
            // indistinguishable from the empty section they both produce.
            log.warn("[Azure cost] Response carried {} of {} requested dimensions. Asked for {},"
                            + " response columns were {}", present.size(), grouping.size(),
                    grouping.stream().map(Grouping::name).toList(), index.keySet());
        }
        if (presentMetrics.isEmpty()) {
            // No cost column: nothing here is readable, whatever else came back.
            return Result.EMPTY;
        }
        Integer currencyIdx = firstIndex(index, "currency", "billingcurrency", "billingcurrencycode");

        Map<String, Row> folded = new LinkedHashMap<>();
        String currency = null;

        for (JsonNode raw : rawRows) {
            if (!raw.isArray()) {
                continue;
            }
            Map<String, String> dimensions = new LinkedHashMap<>();
            StringBuilder key = new StringBuilder();
            for (Grouping g : present) {
                String value = text(raw, groupingColumns.get(g.columnName()));
                dimensions.put(g.columnName(), value);
                key.append(value).append('\u0000');
            }

            Row row = folded.computeIfAbsent(key.toString(),
                    k -> new Row(dimensions, new LinkedHashMap<>()));
            for (String metric : presentMetrics) {
                Integer at = index.get(metric.toLowerCase(Locale.ROOT));
                row.measures().merge(metric, number(raw, at), Double::sum);
            }

            if (currency == null && currencyIdx != null) {
                String code = text(raw, currencyIdx);
                if (!code.isBlank()) {
                    currency = code;
                }
            }
        }

        return new Result(List.copyOf(folded.values()), present, presentMetrics, currency);
    }

    private static Integer firstIndex(Map<String, Integer> index, String... names) {
        for (String name : names) {
            Integer at = index.get(name);
            if (at != null) {
                return at;
            }
        }
        return null;
    }

    private static String text(JsonNode row, Integer at) {
        if (at == null || row.size() <= at) {
            return "";
        }
        JsonNode value = row.get(at);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static double number(JsonNode row, Integer at) {
        if (at == null || row.size() <= at) {
            return 0.0;
        }
        JsonNode value = row.get(at);
        return value == null || value.isNull() ? 0.0 : value.asDouble(0.0);
    }
}
