package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.costmanagement.CostManagementManager;
import com.azure.resourcemanager.costmanagement.models.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AzureCostQueryService {

    private static final Logger log = LoggerFactory.getLogger(AzureCostQueryService.class);

    /** Hard ceiling on continuation pages, so a pathological subscription cannot pin a worker. */
    private static final int MAX_COST_PAGES = 50;

    private final AzureClientProvider clientProvider;

    /**
     * Shared ARM transport: connection reuse, connect and request timeouts, and retry that
     * honours Azure's retry-after header.
     *
     * <p>
     * This class previously built {@code HttpClient.newHttpClient()} inline at two call sites with
     * none of that. Cost Management is the most aggressively throttled endpoint in the Azure
     * integration — two back-to-back {@code query} calls against a live subscription were enough
     * to exhaust the client-type quota — and a 429 here returns an empty result, which the
     * ingestion job logs as "no cost data" and the dashboard renders as zero spend.
     */
    private final AzureArmClient armClient;

    public AzureCostQueryService(AzureClientProvider clientProvider, AzureArmClient armClient) {
        this.clientProvider = clientProvider;
        this.armClient = armClient;
    }

    /** The rows of a Cost Management query, with the column metadata needed to read them. */
    private record CostQueryResult(JsonNode columns, List<JsonNode> rows) {
        boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /**
     * Runs a Cost Management query and returns every row, following {@code nextLink}.
     *
     * <p>
     * The response carries a {@code properties.nextLink} when the result does not fit in one page,
     * and it was being ignored: only the first page was ever read. Whether that truncated anything
     * depended entirely on how many rows the grouping produced, so the same code was correct for a
     * small subscription and silently wrong for a large one, with no error either way. Checked
     * against a live subscription — 8 meter categories over 200 days, 1546 rows — where Azure
     * returned everything in one page and {@code nextLink} was null, so that account was never
     * affected. The grouping decides it, not the account: swapping MeterCategory for ResourceId
     * multiplies the row count by the resource count.
     *
     * <p>
     * The continuation is a POST with the same body, not a GET — the query definition has to be
     * resent with the cursor.
     */
    private CostQueryResult runCostQuery(String subscriptionId, String token, String body) {
        String url = "https://management.azure.com/subscriptions/" + subscriptionId
                + "/providers/Microsoft.CostManagement/query?api-version=2023-03-01";

        List<JsonNode> allRows = new ArrayList<>();
        JsonNode columns = null;
        int pages = 0;
        ObjectMapper mapper = new ObjectMapper();

        while (url != null && !url.isBlank() && pages < MAX_COST_PAGES) {
            Optional<String> response = armClient.post(url, token, body);
            if (response.isEmpty()) {
                break;
            }
            try {
                JsonNode root = mapper.readTree(response.get());
                JsonNode properties = root.path("properties");
                if (columns == null) {
                    columns = properties.path("columns");
                }
                JsonNode rows = properties.path("rows");
                if (rows.isArray()) {
                    rows.forEach(allRows::add);
                }
                url = properties.path("nextLink").asText(null);
            } catch (Exception e) {
                log.warn("Could not parse Cost Management response for {}: {}", subscriptionId, e.getMessage());
                break;
            }
            pages++;
        }

        if (pages >= MAX_COST_PAGES) {
            log.warn("Cost Management query for {} hit the {}-page ceiling; results may be truncated",
                    subscriptionId, MAX_COST_PAGES);
        }
        if (pages > 1) {
            log.info("Cost Management query for {} returned {} rows across {} pages",
                    subscriptionId, allRows.size(), pages);
        }
        return new CostQueryResult(columns == null ? mapper.createArrayNode() : columns, allRows);
    }

    /**
     * Fetches cost data for the given account using the Query API.
     * Returns a map of MonthYear -> Cost (Actual)
     */
    public Map<String, Double> fetchCostData(CloudAccount account) {
        String subscriptionId = account.getAzureSubscriptionId();
        String scope = "/subscriptions/" + subscriptionId;

        log.info("Fetching cost data via API for account: {}", subscriptionId);

        try {
            TokenCredential credential = clientProvider.getCredential(account);
            AzureProfile profile = new AzureProfile(account.getAzureTenantId(), account.getAzureSubscriptionId(),
                    com.azure.core.management.AzureEnvironment.AZURE);

            CostManagementManager costManager = CostManagementManager.authenticate(credential, profile);

            // Define time period (e.g., last 30 days or current month)
            // For a full backfill/check, we might want current month + previous month
            // But for daily ingestion, let's focus on Month-to-Date

            // We want to query by Service (ProductName), Location, and Date to match the
            // detailed CSV structure
            // But for the simple "Cost History" and basic cache, existing ingestion
            // aggregates by Month+Forecast.

            // Let's create a query that aggregates by Date first, so we can construct the
            // daily data

            QueryDataset dataset = new QueryDataset()
                    .withGranularity(GranularityType.DAILY)
                    .withAggregation(
                            Map.of("TotalCost", new QueryAggregation().withName("Cost").withFunction(FunctionType.SUM)))
                    .withGrouping(List.of(
                            new QueryGrouping().withType(QueryColumnType.DIMENSION).withName("MeterCategory"),
                            new QueryGrouping().withType(QueryColumnType.DIMENSION).withName("ResourceLocation"),
                            new QueryGrouping().withType(QueryColumnType.DIMENSION).withName("ChargeType") // To check
                                                                                                           // for
                                                                                                           // Forecast/Actual
                                                                                                           // if
                                                                                                           // supported,
                                                                                                           // but Query
                                                                                                           // API
                                                                                                           // usually
                                                                                                           // returns
                                                                                                           // Actual for
                                                                                                           // historical
                    ));

            QueryDefinition queryDef = new QueryDefinition()
                    .withType(ExportType.ACTUAL_COST)
                    .withTimeframe(TimeframeType.MONTH_TO_DATE)
                    .withDataset(dataset);

            log.info("Executing cost query for {}", subscriptionId);

            QueryResult result = costManager.queries().usage(scope, queryDef);

            // Parse result
            List<List<Object>> rows = result.rows();
            log.info("Query returned {} rows", rows.size());

            Map<String, Double> costByService = new HashMap<>();
            Map<String, Double> costByDate = new HashMap<>(); // MonthYear -> Cost
            Map<String, Double> costByRegion = new HashMap<>();

            for (List<Object> row : rows) {
                // Expected columns based on grouping:
                // [Cost, UsageDate, ProductName, ResourceLocation, ChargeType]
                // Note: The order depends on how Azure returns it, usually Cost is first if
                // aggregated

                // We need to inspect columns from result.columns() to be sure of index
                // But typically: [Cost, UsageDate, Group1, Group2...]

                // Let's rely on column mapping if possible, but the SDK returns List<Object>
                // We can iterate columns to find indices.

                double cost = 0.0;
                String service = "Unknown";
                String region = "Unassigned";
                String dateStr = "";

                // Simple index-based mapping (robustness required here)
                // Assuming standard order: Cost, UsageDate, Groupings...
                if (row.size() >= 2) {
                    try {
                        cost = Double.parseDouble(row.get(0).toString());
                    } catch (NumberFormatException e) {
                        cost = 0.0;
                    }
                    dateStr = row.get(1).toString(); // 2023-10-25T00:00:00...

                    if (row.size() > 2)
                        service = row.get(2).toString();
                    if (row.size() > 3)
                        region = row.get(3).toString();
                }

                // Aggregate
                costByService.put(service, costByService.getOrDefault(service, 0.0) + cost);
                costByRegion.put(region, costByRegion.getOrDefault(region, 0.0) + cost);

                // Date parsing
                // Assuming standard Azure date format 20231025 or ISO
                // Actually usage Date is often long or ISO string
                try {
                    // Check if it's a Long (timestamp) or String
                    LocalDate date;
                    Object dateObj = row.get(1);
                    if (dateObj instanceof Number) {
                        date = LocalDate.ofEpochDay(((Number) dateObj).longValue()); // Rare
                    } else {
                        // 2025-12-23T00:00:00
                        date = LocalDate.parse(dateObj.toString().substring(0, 10));
                    }

                    String monthYear = date.format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy"));
                    costByDate.put(monthYear, costByDate.getOrDefault(monthYear, 0.0) + cost);

                } catch (Exception e) {
                    log.warn("Failed to parse date: " + row.get(1));
                }
            }

            // Return aggregated MonthYear map?
            // The IngestionService expects to handle caching itself.
            // We should probably return a rich object or just the maps.
            // For now, let's just return the Cost By Month string for simplicity in
            // integrating with IngestionService flow,
            // OR we can refactor IngestionService to take these maps.

            // Actually, `AzureBillingDataIngestionService` does everything inside
            // `ingestDataForAccount`.
            // We should probably expose these underlying maps so
            // `AzureBillingDataIngestionService` can cache them.

            // Let's assume this service returns a composite DTO
            return costByDate;

        } catch (Exception e) {
            log.error("Failed to fetch cost data via API for {}: {}", subscriptionId, e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    // Helper DTO class
    public static class AzureCostData {
        public Map<String, Double> costByService = new HashMap<>();
        public Map<String, Double> costByDate = new HashMap<>();
        public Map<String, Double> costByRegion = new HashMap<>();
        public Map<String, Double> currentMonthCostByService = new HashMap<>();
        public Map<String, Double> currentMonthCostByRegion = new HashMap<>();
        public Map<String, Map<String, Double>> costByServiceByDate = new HashMap<>();

        /**
         * Daily cost per service: service name -&gt; ISO date -&gt; cost.
         *
         * <p>
         * The Cost Management query already runs at {@code Daily} granularity, but every consumer
         * collapsed the result into {@code MMM yyyy} buckets and the day was discarded. Anomaly
         * detection needs the daily series: a service that spends its usual month in three days is
         * invisible in a monthly total, and that is exactly the shape of a runaway resource. Keyed
         * by ISO date string rather than {@link java.time.LocalDate} so it round-trips through the
         * JSON cache without a key deserializer.
         */
        public Map<String, Map<String, Double>> costByServiceByDay = new HashMap<>();
    }

    /**
     * Fetches detailed cost data using the Cost Management REST API directly (api-version=2023-03-01).
     * The azure-resourcemanager-costmanagement SDK v1.0.0 (API 2021-10-01) silently returns 0 rows
     * for CUSTOM timeframe queries, so we bypass it with a direct HTTP call.
     */
    public AzureCostData fetchDetailedCostData(CloudAccount account) {
        AzureCostData data = new AzureCostData();
        String subscriptionId = account.getAzureSubscriptionId();

        try {
            TokenCredential credential = clientProvider.getCredential(account);

            // Obtain bearer token for Azure management API
            TokenRequestContext tokenCtx = new TokenRequestContext()
                    .addScopes("https://management.azure.com/.default");
            String bearerToken = credential.getToken(tokenCtx).block().getToken();

            // 6-month lookback — captures historical cost trends even when current month is empty
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusMonths(6).withDayOfMonth(1);

            // Request body matching the confirmed-working PowerShell test (api-version=2023-03-01)
            String body = String.format(
                    "{\"type\":\"ActualCost\",\"timeframe\":\"Custom\"," +
                    "\"timePeriod\":{\"from\":\"%sT00:00:00Z\",\"to\":\"%sT00:00:00Z\"}," +
                    "\"dataset\":{\"granularity\":\"Daily\"," +
                    "\"aggregation\":{\"TotalCost\":{\"name\":\"Cost\",\"function\":\"Sum\"}}," +
                    "\"grouping\":[" +
                    "{\"type\":\"Dimension\",\"name\":\"MeterCategory\"}," +
                    "{\"type\":\"Dimension\",\"name\":\"ResourceLocation\"}" +
                    "]}}",
                    startDate, endDate);

            log.info("Querying Cost Management REST API for {} from {} to {}", subscriptionId, startDate, endDate);

            // Timeouts, throttle retry and nextLink paging all live in runCostQuery now. A 429 here
            // used to fall straight through to the "0 rows" branch below, which reports it as a
            // permissions or no-activity problem — three plausible causes listed, none of them the
            // actual one — and leaves the cost caches cold so the dashboard shows zero spend.
            CostQueryResult result = runCostQuery(subscriptionId, bearerToken, body);
            JsonNode columns = result.columns();
            List<JsonNode> rows = result.rows();

            // Build column index map from response column metadata
            int costIdx = 0, dateIdx = 1, productIdx = 2, locationIdx = 3; // positional defaults
            if (columns.isArray() && columns.size() > 0) {
                costIdx = -1; dateIdx = -1; productIdx = -1; locationIdx = -1;
                for (int i = 0; i < columns.size(); i++) {
                    String name = columns.get(i).path("name").asText();
                    if (name.equalsIgnoreCase("Cost") || name.equalsIgnoreCase("TotalCost"))
                        costIdx = i;
                    else if (name.equalsIgnoreCase("UsageDate") || name.equalsIgnoreCase("BillingMonth")
                            || name.equalsIgnoreCase("Date"))
                        dateIdx = i;
                    else if (name.equalsIgnoreCase("MeterCategory") || name.equalsIgnoreCase("ProductName")
                            || name.equalsIgnoreCase("ServiceName"))
                        productIdx = i;
                    else if (name.equalsIgnoreCase("ResourceLocation") || name.equalsIgnoreCase("Location"))
                        locationIdx = i;
                }
            }

            List<String> colNames = new ArrayList<>();
            if (columns.isArray()) {
                for (JsonNode col : columns) colNames.add(col.path("name").asText());
            }
            log.info("REST API Result Columns: [{}], Row Count: {}", String.join(", ", colNames), rows.size());

            if (rows.isEmpty()) {
                log.warn("Cost Management REST API returned 0 rows for subscription {}. " +
                        "Possible causes: (1) SP '{}' lacks 'Cost Management Reader' role, " +
                        "(2) Azure tenant policy restricts cost data access to billing admins, " +
                        "(3) No billable activity in the last 6 months. " +
                        "Throttling is no longer a candidate — a 429 is retried and then raised.",
                        subscriptionId, account.getAzureClientId());
                return data;
            }

            for (JsonNode row : rows) {
                if (!row.isArray()) continue;
                if (costIdx == -1 || dateIdx == -1 || row.size() <= Math.max(costIdx, dateIdx))
                    continue;

                double cost = row.get(costIdx).asDouble();
                String service = (productIdx != -1 && row.size() > productIdx)
                        ? row.get(productIdx).asText() : "Unknown";
                String region = (locationIdx != -1 && row.size() > locationIdx)
                        ? row.get(locationIdx).asText() : "Unknown";

                data.costByService.put(service, data.costByService.getOrDefault(service, 0.0) + cost);
                data.costByRegion.put(region, data.costByRegion.getOrDefault(region, 0.0) + cost);

                try {
                    // UsageDate is returned as a numeric YYYYMMDD integer (e.g. 20260115)
                    LocalDate date;
                    JsonNode dateNode = row.get(dateIdx);
                    if (dateNode.isNumber()) {
                        String s = String.valueOf(dateNode.asLong());
                        date = LocalDate.parse(s, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                    } else {
                        date = LocalDate.parse(dateNode.asText().substring(0, 10));
                    }
                    String monthYear = date.format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy"));
                    data.costByDate.put(monthYear, data.costByDate.getOrDefault(monthYear, 0.0) + cost);

                    // Track per-service monthly costs for service trend chart
                    data.costByServiceByDate
                            .computeIfAbsent(service, k -> new HashMap<>())
                            .merge(monthYear, cost, Double::sum);

                    // Keep the daily grain too. The query is already daily; collapsing it to months
                    // here was throwing away the only series a spike can be seen in.
                    data.costByServiceByDay
                            .computeIfAbsent(service, k -> new HashMap<>())
                            .merge(date.toString(), cost, Double::sum);

                    // Track current month costs per service and region for Billing Details
                    String currentMonthLabel = LocalDate.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy"));
                    if (monthYear.equals(currentMonthLabel)) {
                        data.currentMonthCostByService.put(service,
                                data.currentMonthCostByService.getOrDefault(service, 0.0) + cost);
                        data.currentMonthCostByRegion.put(region,
                                data.currentMonthCostByRegion.getOrDefault(region, 0.0) + cost);
                    }
                } catch (Exception e) {
                    // ignore date parse error for individual rows
                }
            }

            return data;

        } catch (Exception e) {
            log.error("Cost Management REST API Fetch Error for {}: {}", subscriptionId, e.getMessage());
            return data;
        }
    }

    /**
     * Fetches cost breakdown (by service and region) for an explicit date range.
     * Used by the Cost Explorer comparison feature.
     */
    public AzureCostData fetchCostBreakdownForDateRange(CloudAccount account, String startDate, String endDate) {
        AzureCostData data = new AzureCostData();
        String subscriptionId = account.getAzureSubscriptionId();
        try {
            TokenCredential credential = clientProvider.getCredential(account);
            TokenRequestContext tokenCtx = new TokenRequestContext()
                    .addScopes("https://management.azure.com/.default");
            String bearerToken = credential.getToken(tokenCtx).block().getToken();

            String body = String.format(
                    "{\"type\":\"ActualCost\",\"timeframe\":\"Custom\"," +
                    "\"timePeriod\":{\"from\":\"%sT00:00:00Z\",\"to\":\"%sT00:00:00Z\"}," +
                    "\"dataset\":{\"granularity\":\"Daily\"," +
                    "\"aggregation\":{\"TotalCost\":{\"name\":\"Cost\",\"function\":\"Sum\"}}," +
                    "\"grouping\":[" +
                    "{\"type\":\"Dimension\",\"name\":\"MeterCategory\"}," +
                    "{\"type\":\"Dimension\",\"name\":\"ResourceLocation\"}" +
                    "]}}",
                    startDate, endDate);

            CostQueryResult result = runCostQuery(subscriptionId, bearerToken, body);
            JsonNode columns = result.columns();
            List<JsonNode> rows = result.rows();

            int costIdx = 0, productIdx = 2, locationIdx = 3;
            if (columns.isArray() && columns.size() > 0) {
                costIdx = -1; productIdx = -1; locationIdx = -1;
                for (int i = 0; i < columns.size(); i++) {
                    String name = columns.get(i).path("name").asText();
                    if (name.equalsIgnoreCase("Cost") || name.equalsIgnoreCase("TotalCost")) costIdx = i;
                    else if (name.equalsIgnoreCase("MeterCategory") || name.equalsIgnoreCase("ProductName")
                            || name.equalsIgnoreCase("ServiceName")) productIdx = i;
                    else if (name.equalsIgnoreCase("ResourceLocation") || name.equalsIgnoreCase("Location")) locationIdx = i;
                }
            }

            for (JsonNode row : rows) {
                if (!row.isArray() || costIdx == -1 || row.size() <= costIdx) continue;
                double cost = row.get(costIdx).asDouble();
                String service = (productIdx != -1 && row.size() > productIdx) ? row.get(productIdx).asText() : "Unknown";
                String region  = (locationIdx != -1 && row.size() > locationIdx) ? row.get(locationIdx).asText() : "Unknown";
                data.costByService.merge(service, cost, (a, b) -> a + b);
                data.costByRegion.merge(region, cost, (a, b) -> a + b);
            }

        } catch (Exception e) {
            log.error("Cost Management date-range fetch error for {}: {}", subscriptionId, e.getMessage());
        }
        return data;
    }
}
