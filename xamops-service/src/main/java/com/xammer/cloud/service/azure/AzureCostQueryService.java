package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.costmanagement.CostManagementManager;
import com.azure.resourcemanager.costmanagement.models.*;
import com.xammer.cloud.domain.CloudAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AzureCostQueryService {

    private static final Logger log = LoggerFactory.getLogger(AzureCostQueryService.class);
    private final AzureClientProvider clientProvider;

    public AzureCostQueryService(AzureClientProvider clientProvider) {
        this.clientProvider = clientProvider;
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
    }

    public AzureCostData fetchDetailedCostData(CloudAccount account) {
        AzureCostData data = new AzureCostData();
        String subscriptionId = account.getAzureSubscriptionId();
        String scope = "/subscriptions/" + subscriptionId;

        try {
            TokenCredential credential = clientProvider.getCredential(account);
            AzureProfile profile = new AzureProfile(account.getAzureTenantId(), account.getAzureSubscriptionId(),
                    com.azure.core.management.AzureEnvironment.AZURE);
            CostManagementManager costManager = CostManagementManager.authenticate(credential, profile);

            QueryDataset dataset = new QueryDataset()
                    .withGranularity(GranularityType.DAILY)
                    .withAggregation(
                            Map.of("TotalCost", new QueryAggregation().withName("Cost").withFunction(FunctionType.SUM)))
                    .withGrouping(List.of(
                            new QueryGrouping().withType(QueryColumnType.DIMENSION).withName("MeterCategory"),
                            new QueryGrouping().withType(QueryColumnType.DIMENSION).withName("ResourceLocation")));

            QueryDefinition queryDef = new QueryDefinition()
                    .withType(ExportType.ACTUAL_COST)
                    .withTimeframe(TimeframeType.MONTH_TO_DATE)
                    .withDataset(dataset);

            QueryResult result = costManager.queries().usage(scope, queryDef);

            String columnNames = result.columns().stream()
                    .map(c -> c.name())
                    .collect(Collectors.joining(", "));
            log.info("API Result Columns: {}", columnNames);
            log.info("API Result Row Count: {}", result.rows().size());

            for (List<Object> row : result.rows()) {
                // Determine indices based on columns
                int costIdx = -1;
                int dateIdx = -1;
                int productIdx = -1;
                int locationIdx = -1;

                for (int i = 0; i < result.columns().size(); i++) {
                    String name = result.columns().get(i).name();
                    if (name.equalsIgnoreCase("Cost"))
                        costIdx = i;
                    else if (name.equalsIgnoreCase("UsageDate"))
                        dateIdx = i;
                    else if (name.equalsIgnoreCase("MeterCategory"))
                        productIdx = i;
                    else if (name.equalsIgnoreCase("ResourceLocation"))
                        locationIdx = i;
                }

                if (costIdx == -1 || dateIdx == -1)
                    continue;

                double cost = Double.parseDouble(row.get(costIdx).toString());
                String service = productIdx != -1 ? row.get(productIdx).toString() : "Unknown";
                String region = locationIdx != -1 ? row.get(locationIdx).toString() : "Unknown";
                String dateStr = row.get(dateIdx).toString();

                // Aggregations
                data.costByService.put(service, data.costByService.getOrDefault(service, 0.0) + cost);
                data.costByRegion.put(region, data.costByRegion.getOrDefault(region, 0.0) + cost);

                try {
                    // dateStr is usually "20231025" (Long) or "2023-10-25..."
                    LocalDate date;
                    Object dateObj = row.get(dateIdx);
                    if (dateObj instanceof Number) {
                        // numeric YYYYMMDD
                        String s = String.valueOf(((Number) dateObj).longValue());
                        date = LocalDate.parse(s, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                    } else {
                        date = LocalDate.parse(dateObj.toString().substring(0, 10));
                    }
                    String monthYear = date.format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy"));
                    data.costByDate.put(monthYear, data.costByDate.getOrDefault(monthYear, 0.0) + cost);
                } catch (Exception e) {
                    // ignore date parse error
                }
            }

            return data;

        } catch (Exception e) {
            log.error("API Fetch Error: {}", e.getMessage());
            return data;
        }
    }
}
