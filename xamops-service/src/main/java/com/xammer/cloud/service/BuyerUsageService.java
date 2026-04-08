package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.IsvMarketplaceCustomer;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.IsvMarketplaceCustomerRepository;
import com.xammer.cloud.repository.IsvProductRepository;
import com.xammer.cloud.repository.UsageEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buyer-facing usage visibility service.
 *
 * Buyers can see what dimensions are being metered on their behalf across all
 * ISV products they have subscribed to. Useful for understanding why they are
 * charged a certain amount on their AWS Marketplace bill.
 *
 * Identification: the buyer's XamOps cloud account ID is resolved to the AWS
 * account ID via the cloud_account table, then matched against
 * IsvMarketplaceCustomer.customerAwsAccountId.
 */
@Service
@Slf4j
public class BuyerUsageService {

    private final IsvMarketplaceCustomerRepository customerRepository;
    private final IsvProductRepository productRepository;
    private final UsageEventRepository usageEventRepository;
    private final CloudAccountRepository cloudAccountRepository;

    public BuyerUsageService(IsvMarketplaceCustomerRepository customerRepository,
                              IsvProductRepository productRepository,
                              UsageEventRepository usageEventRepository,
                              CloudAccountRepository cloudAccountRepository) {
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.usageEventRepository = usageEventRepository;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Returns all marketplace subscriptions metered for the buyer's cloud account,
     * with the last 30 days of usage per dimension.
     *
     * @param cloudAccountId the XamOps internal cloud account ID (Long as String)
     * @return list of subscriptions with usage summaries, or empty list if none found
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getBuyerUsage(String cloudAccountId) {
        if (cloudAccountId == null || cloudAccountId.isBlank()) {
            throw new IllegalArgumentException("cloudAccountId is required");
        }

        // Resolve XamOps cloud account ID → AWS account number
        Long dbId;
        try {
            dbId = Long.parseLong(cloudAccountId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cloudAccountId: " + cloudAccountId);
        }

        CloudAccount account = cloudAccountRepository.findById(dbId).orElse(null);
        if (account == null) {
            log.debug("Cloud account not found: id={}", cloudAccountId);
            return List.of();
        }

        String awsAccountId = account.getAwsAccountId();
        if (awsAccountId == null || awsAccountId.isBlank()) {
            log.debug("No AWS account ID on cloud account id={}", cloudAccountId);
            return List.of();
        }

        List<IsvMarketplaceCustomer> subscriptions =
                customerRepository.findByCustomerAwsAccountId(awsAccountId);

        if (subscriptions.isEmpty()) {
            log.debug("No marketplace subscriptions found for awsAccountId={}", awsAccountId);
            return List.of();
        }

        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<Map<String, Object>> result = new ArrayList<>();

        for (IsvMarketplaceCustomer sub : subscriptions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("productCode", sub.getProductCode());
            entry.put("customerIdentifier", sub.getCustomerIdentifier());
            entry.put("status", sub.getStatus());
            entry.put("registeredAt", sub.getRegisteredAt() != null ? sub.getRegisteredAt().toString() : null);
            entry.put("cancelledAt", sub.getCancelledAt() != null ? sub.getCancelledAt().toString() : null);

            // Product name (best-effort — falls back to productCode if product was deregistered)
            String productName = productRepository.findByProductCode(sub.getProductCode())
                    .map(p -> p.getProductName())
                    .orElse(sub.getProductCode());
            entry.put("productName", productName);

            // Dimension usage for last 30 days (SUBMITTED events only)
            List<Object[]> rows = usageEventRepository.sumByDimensionForCustomer(
                    sub.getProductCode(), sub.getCustomerIdentifier(), since);

            List<Map<String, Object>> dimensions = new ArrayList<>();
            for (Object[] row : rows) {
                Map<String, Object> dim = new LinkedHashMap<>();
                dim.put("dimension", row[0]);
                dim.put("totalQuantity", row[1] != null ? ((Number) row[1]).longValue() : 0L);
                dimensions.add(dim);
            }
            entry.put("usageLast30Days", dimensions);
            entry.put("periodStart", since.toString());
            entry.put("periodEnd", LocalDateTime.now().toString());

            result.add(entry);
        }

        log.debug("Buyer usage for cloudAccountId={} (awsAccountId={}): {} subscription(s)",
                cloudAccountId, awsAccountId, result.size());
        return result;
    }
}
