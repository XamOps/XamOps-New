package com.xammer.cloud.service;

import com.xammer.cloud.domain.IsvProduct;
import com.xammer.cloud.domain.MeteringRecord;
import com.xammer.cloud.domain.UsageEvent;
import com.xammer.cloud.repository.IsvProductRepository;
import com.xammer.cloud.repository.MeteringRecordRepository;
import com.xammer.cloud.repository.UsageEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.marketplacemetering.MarketplaceMeteringClient;
import software.amazon.awssdk.services.marketplacemetering.model.BatchMeterUsageRequest;
import software.amazon.awssdk.services.marketplacemetering.model.BatchMeterUsageResponse;
import software.amazon.awssdk.services.marketplacemetering.model.UsageRecord;
import software.amazon.awssdk.services.marketplacemetering.model.UsageRecordResult;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Core metering engine: aggregates pending usage events and submits them to
 * AWS Marketplace via BatchMeterUsage.
 *
 * Processing flow (run hourly by MeteringScheduler):
 *   1. Load all PENDING usage events
 *   2. Group by (productCode, customerIdentifier, dimension) and sum quantities
 *   3. For each product, assume the ISV's IAM role via STS
 *   4. Call BatchMeterUsage (max 25 records per call)
 *   5. Save MeteringRecord with AWS response (record ID + status)
 *   6. Mark source UsageEvents as SUBMITTED or FAILED
 *
 * AWS BatchMeterUsage constraints:
 *   - Max 25 UsageRecord per call
 *   - Timestamp must be within the past 1 hour (we use the current truncated hour)
 *   - Duplicate records (same customer+dimension+hour) are idempotent (AWS returns DuplicateRecord)
 */
@Service
@Slf4j
public class MeteringEngineService {

    private static final int BATCH_SIZE = 25; // AWS BatchMeterUsage limit

    private final IsvProductRepository productRepository;
    private final UsageEventRepository usageEventRepository;
    private final MeteringRecordRepository meteringRecordRepository;
    private final StsClient stsClient;
    private final SnsNotificationService snsNotificationService;

    public MeteringEngineService(IsvProductRepository productRepository,
                                  UsageEventRepository usageEventRepository,
                                  MeteringRecordRepository meteringRecordRepository,
                                  StsClient stsClient,
                                  SnsNotificationService snsNotificationService) {
        this.productRepository = productRepository;
        this.usageEventRepository = usageEventRepository;
        this.meteringRecordRepository = meteringRecordRepository;
        this.stsClient = stsClient;
        this.snsNotificationService = snsNotificationService;
    }

    // ─── Main processing entry point ──────────────────────────────────────────

    /**
     * Processes all pending usage events across all active ISV products.
     * Called by MeteringScheduler every hour.
     *
     * @return summary map with per-product submission results
     */
    @Transactional
    public Map<String, Object> processPendingEvents() {
        List<UsageEvent> allPending = usageEventRepository.findAllPending();
        if (allPending.isEmpty()) {
            log.info("MeteringEngine: no pending events to process");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "NO_PENDING_EVENTS");
            result.put("processedAt", LocalDateTime.now().toString());
            return result;
        }

        log.info("MeteringEngine: processing {} pending events", allPending.size());

        // Group events by productCode
        Map<String, List<UsageEvent>> byProduct = allPending.stream()
                .collect(Collectors.groupingBy(UsageEvent::getProductCode));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("processedAt", LocalDateTime.now().toString());
        summary.put("totalPendingEvents", allPending.size());

        List<Map<String, Object>> productResults = new ArrayList<>();

        for (Map.Entry<String, List<UsageEvent>> entry : byProduct.entrySet()) {
            String productCode = entry.getKey();
            List<UsageEvent> events = entry.getValue();
            try {
                Map<String, Object> productResult = processProductEvents(productCode, events);
                productResults.add(productResult);
            } catch (Exception e) {
                log.error("MeteringEngine: failed to process events for product={}: {}", productCode, e.getMessage(), e);
                // Mark all events for this product as FAILED so they don't block the queue
                markEventsFailed(events, "Engine error: " + e.getMessage());
                Map<String, Object> errorResult = new LinkedHashMap<>();
                errorResult.put("productCode", productCode);
                errorResult.put("status", "ERROR");
                errorResult.put("error", e.getMessage());
                errorResult.put("eventsAffected", events.size());
                productResults.add(errorResult);
            }
        }

        summary.put("products", productResults);
        log.info("MeteringEngine: completed processing. Products processed: {}", byProduct.size());
        return summary;
    }

    // ─── Per-product processing ───────────────────────────────────────────────

    private Map<String, Object> processProductEvents(String productCode, List<UsageEvent> events) {
        IsvProduct product = productRepository.findByProductCode(productCode)
                .orElseThrow(() -> new IllegalStateException("Product not found: " + productCode));

        if (!"ACTIVE".equals(product.getStatus())) {
            log.warn("Skipping events for inactive product: {}", productCode);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("productCode", productCode);
            r.put("status", "SKIPPED_INACTIVE");
            return r;
        }

        // Aggregate: group by (customerIdentifier, dimension), sum quantities
        // Use the current truncated hour as the billing timestamp
        Instant billingHour = Instant.now().truncatedTo(ChronoUnit.HOURS);

        Map<String, AggregatedUsage> aggregated = new LinkedHashMap<>();
        for (UsageEvent event : events) {
            String key = event.getCustomerIdentifier() + "|" + event.getDimension();
            aggregated.computeIfAbsent(key, k -> new AggregatedUsage(
                    event.getCustomerIdentifier(), event.getDimension(), new ArrayList<>()))
                    .events.add(event);
        }

        // Build UsageRecord list for BatchMeterUsage
        List<UsageRecord> records = aggregated.values().stream().map(agg -> {
            long totalQty = agg.events.stream().mapToLong(UsageEvent::getQuantity).sum();
            return UsageRecord.builder()
                    .customerIdentifier(agg.customerIdentifier)
                    .dimension(agg.dimension)
                    .quantity((int) Math.min(totalQty, Integer.MAX_VALUE))
                    .timestamp(billingHour)
                    .build();
        }).collect(Collectors.toList());

        log.info("Submitting {} aggregated records for product={} (from {} raw events)",
                records.size(), productCode, events.size());

        // Submit in batches of 25 (AWS limit)
        StaticCredentialsProvider creds = assumeRole(product.getRoleArn(), product.getExternalId());
        int successCount = 0;
        int failCount = 0;

        List<List<UsageRecord>> batches = partition(records, BATCH_SIZE);
        List<AggregatedUsage> aggList = new ArrayList<>(aggregated.values());

        try (MarketplaceMeteringClient client = MarketplaceMeteringClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build()) {

            for (List<UsageRecord> batch : batches) {
                try {
                    BatchMeterUsageResponse response = client.batchMeterUsage(
                            BatchMeterUsageRequest.builder()
                                    .productCode(productCode)
                                    .usageRecords(batch)
                                    .build());

                    for (UsageRecordResult result : response.results()) {
                        String customerId = result.usageRecord().customerIdentifier();
                        String dimension = result.usageRecord().dimension();
                        String statusStr = result.statusAsString();

                        boolean success = "Success".equalsIgnoreCase(statusStr)
                                || "DuplicateRecord".equalsIgnoreCase(statusStr);

                        // Customer is no longer subscribed — expire them automatically
                        if ("CustomerNotSubscribed".equalsIgnoreCase(statusStr)) {
                            snsNotificationService.expireCustomer(productCode, customerId,
                                    "BatchMeterUsage returned CustomerNotSubscribed");
                        }

                        // Save MeteringRecord (proof of submission)
                        MeteringRecord rec = new MeteringRecord();
                        rec.setProductCode(productCode);
                        rec.setCustomerIdentifier(customerId);
                        rec.setDimension(dimension);
                        rec.setQuantity((long) result.usageRecord().quantity());
                        rec.setUsageTimestamp(billingHour);
                        rec.setAwsMeteringRecordId(result.meteringRecordId());
                        rec.setStatus(success ? "SUCCESS" : "FAILED");
                        if (!success) {
                            rec.setErrorMessage("AWS status: " + statusStr);
                        }
                        MeteringRecord savedRec = meteringRecordRepository.save(rec);

                        // Mark corresponding UsageEvents
                        String key = customerId + "|" + dimension;
                        AggregatedUsage agg = aggregated.get(key);
                        if (agg != null) {
                            String eventStatus = success ? "SUBMITTED" : "FAILED";
                            for (UsageEvent event : agg.events) {
                                event.setStatus(eventStatus);
                                event.setProcessedAt(LocalDateTime.now());
                                event.setMeteringRecordId(savedRec.getId());
                                usageEventRepository.save(event);
                            }
                        }

                        if (success) successCount++;
                        else failCount++;
                    }

                    // Handle unprocessed records (customer not subscribed etc.)
                    if (response.hasUnprocessedRecords()) {
                        for (UsageRecord unprocessed : response.unprocessedRecords()) {
                            String customerId = unprocessed.customerIdentifier();
                            String dimension = unprocessed.dimension();
                            String key = customerId + "|" + dimension;

                            MeteringRecord rec = new MeteringRecord();
                            rec.setProductCode(productCode);
                            rec.setCustomerIdentifier(customerId);
                            rec.setDimension(dimension);
                            rec.setQuantity((long) unprocessed.quantity());
                            rec.setUsageTimestamp(billingHour);
                            rec.setStatus("FAILED");
                            rec.setErrorMessage("AWS returned as unprocessed record");
                            MeteringRecord savedRec = meteringRecordRepository.save(rec);

                            AggregatedUsage agg = aggregated.get(key);
                            if (agg != null) {
                                markEventsFailed(agg.events, "AWS unprocessed: " + rec.getErrorMessage());
                                agg.events.forEach(e -> e.setMeteringRecordId(savedRec.getId()));
                            }
                            failCount++;
                        }
                    }

                } catch (Exception e) {
                    log.error("BatchMeterUsage call failed for product={}: {}", productCode, e.getMessage());
                    // Fail the events in this batch
                    for (UsageRecord r : batch) {
                        String key = r.customerIdentifier() + "|" + r.dimension();
                        AggregatedUsage agg = aggregated.get(key);
                        if (agg != null) {
                            markEventsFailed(agg.events, "BatchMeterUsage error: " + e.getMessage());
                        }
                    }
                    failCount += batch.size();
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productCode", productCode);
        result.put("status", failCount == 0 ? "OK" : (successCount == 0 ? "ALL_FAILED" : "PARTIAL"));
        result.put("totalEvents", events.size());
        result.put("aggregatedRecords", records.size());
        result.put("successRecords", successCount);
        result.put("failedRecords", failCount);
        return result;
    }

    // ─── Dashboard / query methods ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRecentMeteringRecords(String productCode, int limit) {
        return meteringRecordRepository
                .findByProductCodeOrderBySubmittedAtDesc(productCode, PageRequest.of(0, limit))
                .stream()
                .map(r -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("id", r.getId());
                    dto.put("customerIdentifier", r.getCustomerIdentifier());
                    dto.put("dimension", r.getDimension());
                    dto.put("quantity", r.getQuantity());
                    dto.put("usageTimestamp", r.getUsageTimestamp().toString());
                    dto.put("awsMeteringRecordId", r.getAwsMeteringRecordId());
                    dto.put("status", r.getStatus());
                    dto.put("submittedAt", r.getSubmittedAt().toString());
                    dto.put("errorMessage", r.getErrorMessage());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void markEventsFailed(List<UsageEvent> events, String reason) {
        for (UsageEvent event : events) {
            event.setStatus("FAILED");
            event.setProcessedAt(LocalDateTime.now());
            usageEventRepository.save(event);
        }
        log.warn("Marked {} events as FAILED: {}", events.size(), reason);
    }

    private StaticCredentialsProvider assumeRole(String roleArn, String externalId) {
        software.amazon.awssdk.services.sts.model.AssumeRoleRequest.Builder reqBuilder =
                software.amazon.awssdk.services.sts.model.AssumeRoleRequest.builder()
                        .roleArn(roleArn)
                        .roleSessionName("xamops-metering-engine")
                        .durationSeconds(3600);
        if (externalId != null && !externalId.isBlank()) {
            reqBuilder.externalId(externalId);
        }
        AssumeRoleResponse response = stsClient.assumeRole(reqBuilder.build());
        Credentials c = response.credentials();
        return StaticCredentialsProvider.create(
                AwsSessionCredentials.create(c.accessKeyId(), c.secretAccessKey(), c.sessionToken()));
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    /** Internal aggregation container */
    private static class AggregatedUsage {
        final String customerIdentifier;
        final String dimension;
        final List<UsageEvent> events;

        AggregatedUsage(String customerIdentifier, String dimension, List<UsageEvent> events) {
            this.customerIdentifier = customerIdentifier;
            this.dimension = dimension;
            this.events = events;
        }
    }
}
