package com.xammer.cloud.service;

import com.xammer.cloud.domain.IsvMarketplaceCustomer;
import com.xammer.cloud.domain.IsvProduct;
import com.xammer.cloud.domain.UsageEvent;
import com.xammer.cloud.dto.isv.UsageBatchRequest;
import com.xammer.cloud.dto.isv.UsageEventRequest;
import com.xammer.cloud.repository.IsvMarketplaceCustomerRepository;
import com.xammer.cloud.repository.IsvProductDimensionRepository;
import com.xammer.cloud.repository.UsageEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handles ingestion of usage events from ISV products.
 * Validates the API key, customer status, dimension key, and quantity,
 * then persists PENDING events for the hourly MeteringScheduler to process.
 */
@Service
@Slf4j
public class UsageEventService {

    private static final int MAX_BATCH_SIZE = 100;

    private final UsageEventRepository usageEventRepository;
    private final IsvOnboardingService isvOnboardingService;
    private final IsvMarketplaceCustomerRepository customerRepository;
    private final IsvProductDimensionRepository dimensionRepository;

    public UsageEventService(UsageEventRepository usageEventRepository,
                              IsvOnboardingService isvOnboardingService,
                              IsvMarketplaceCustomerRepository customerRepository,
                              IsvProductDimensionRepository dimensionRepository) {
        this.usageEventRepository = usageEventRepository;
        this.isvOnboardingService = isvOnboardingService;
        this.customerRepository = customerRepository;
        this.dimensionRepository = dimensionRepository;
    }

    // ─── Single event ingestion ───────────────────────────────────────────────

    /**
     * Ingests a single usage event. Authenticated via the ISV's API key.
     *
     * @param apiKey  X-Api-Key header value
     * @param request usage event payload
     * @return saved event summary
     */
    @Transactional
    public Map<String, Object> ingestEvent(String apiKey, UsageEventRequest request) {
        IsvProduct product = isvOnboardingService.getProductByApiKey(apiKey);
        validateAndSaveEvent(product.getProductCode(), request);
        log.debug("Ingested usage event: product={} customer={} dimension={} qty={}",
                product.getProductCode(), request.getCustomerId(), request.getDimension(), request.getQuantity());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "accepted");
        result.put("productCode", product.getProductCode());
        result.put("customerId", request.getCustomerId());
        result.put("dimension", request.getDimension());
        result.put("quantity", request.getQuantity());
        result.put("timestamp", request.getTimestamp() != null ? request.getTimestamp().toString() : Instant.now().toString());
        return result;
    }

    // ─── Batch event ingestion ────────────────────────────────────────────────

    /**
     * Ingests a batch of usage events in a single API call.
     * Returns a summary with accepted/rejected counts.
     */
    @Transactional
    public Map<String, Object> ingestBatch(String apiKey, UsageBatchRequest batch) {
        if (batch.getEvents() == null || batch.getEvents().isEmpty()) {
            throw new IllegalArgumentException("events list is empty");
        }
        if (batch.getEvents().size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Batch size exceeds maximum of " + MAX_BATCH_SIZE);
        }

        IsvProduct product = isvOnboardingService.getProductByApiKey(apiKey);
        String productCode = product.getProductCode();

        int accepted = 0;
        int rejected = 0;
        List<Map<String, String>> errors = new java.util.ArrayList<>();

        for (UsageEventRequest event : batch.getEvents()) {
            try {
                validateAndSaveEvent(productCode, event);
                accepted++;
            } catch (Exception e) {
                rejected++;
                Map<String, String> err = new LinkedHashMap<>();
                err.put("customerId", event.getCustomerId());
                err.put("dimension", event.getDimension());
                err.put("error", e.getMessage());
                errors.add(err);
            }
        }

        log.info("Batch ingestion: product={} accepted={} rejected={}", productCode, accepted, rejected);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productCode", productCode);
        result.put("accepted", accepted);
        result.put("rejected", rejected);
        if (!errors.isEmpty()) result.put("errors", errors);
        return result;
    }

    // ─── Validation & persistence ─────────────────────────────────────────────

    private void validateAndSaveEvent(String productCode, UsageEventRequest request) {
        // 1. Validate required fields
        if (request.getCustomerId() == null || request.getCustomerId().isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (request.getDimension() == null || request.getDimension().isBlank()) {
            throw new IllegalArgumentException("dimension is required");
        }
        if (request.getQuantity() == null || request.getQuantity() < 0) {
            throw new IllegalArgumentException("quantity must be a non-negative integer");
        }

        // 2. Validate timestamp is not too old (AWS rejects records older than 6 hours)
        Instant eventTs = request.getTimestamp() != null ? request.getTimestamp() : Instant.now();
        if (eventTs.isBefore(Instant.now().minusSeconds(6 * 3600))) {
            throw new IllegalArgumentException(
                    "timestamp is older than 6 hours — AWS Marketplace will reject this record");
        }

        // 3. Validate dimension is registered for this product
        if (!dimensionRepository.existsByProductCodeAndDimensionKey(
                productCode, request.getDimension().toLowerCase())) {
            throw new IllegalArgumentException(
                    "Dimension '" + request.getDimension() + "' is not registered for product: " + productCode);
        }

        // 4. Validate customer is ACTIVE
        Optional<IsvMarketplaceCustomer> customerOpt = customerRepository
                .findByProductCodeAndCustomerIdentifier(productCode, request.getCustomerId());
        if (customerOpt.isEmpty()) {
            throw new IllegalArgumentException(
                    "Customer not found: " + request.getCustomerId() + " for product: " + productCode);
        }
        if (!"ACTIVE".equals(customerOpt.get().getStatus())) {
            throw new IllegalArgumentException(
                    "Customer is not active: " + request.getCustomerId() + " (status=" + customerOpt.get().getStatus() + ")");
        }

        // 5. Persist
        UsageEvent event = new UsageEvent();
        event.setProductCode(productCode);
        event.setCustomerIdentifier(request.getCustomerId());
        event.setDimension(request.getDimension().toLowerCase());
        event.setQuantity(request.getQuantity());
        event.setEventTimestamp(eventTs);
        event.setStatus("PENDING");
        usageEventRepository.save(event);
    }

    // ─── Stats queries ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getEventStats(String productCode) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("productCode", productCode);
        stats.put("pendingEvents", usageEventRepository.countPendingByProductCode(productCode));
        stats.put("failedEvents", usageEventRepository.countFailedByProductCode(productCode));
        return stats;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRecentEvents(String productCode, String customerIdentifier) {
        return usageEventRepository
                .findByProductCodeAndCustomerIdentifierOrderByCreatedAtDesc(productCode, customerIdentifier)
                .stream()
                .limit(50)
                .map(e -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("id", e.getId());
                    dto.put("dimension", e.getDimension());
                    dto.put("quantity", e.getQuantity());
                    dto.put("eventTimestamp", e.getEventTimestamp().toString());
                    dto.put("status", e.getStatus());
                    dto.put("createdAt", e.getCreatedAt().toString());
                    return dto;
                })
                .collect(Collectors.toList());
    }
}
