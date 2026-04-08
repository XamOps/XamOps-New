package com.xammer.cloud.service;

import com.xammer.cloud.domain.IsvMarketplaceCustomer;
import com.xammer.cloud.domain.IsvProduct;
import com.xammer.cloud.repository.IsvMarketplaceCustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.marketplacemetering.MarketplaceMeteringClient;
import software.amazon.awssdk.services.marketplacemetering.model.ResolveCustomerRequest;
import software.amazon.awssdk.services.marketplacemetering.model.ResolveCustomerResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles the AWS Marketplace customer registration flow.
 *
 * When a buyer subscribes on AWS Marketplace, AWS redirects them to the ISV's
 * fulfillment URL with an x-amzn-marketplace-token. The ISV's fulfillment URL
 * should be configured to XamOps' registration endpoint. XamOps then:
 *   1. Calls ResolveCustomer(token) using the ISV's IAM role
 *   2. Validates that the productCode matches the ISV's registered product
 *   3. Stores the customer as ACTIVE
 *   4. Returns the customer details to the ISV
 */
@Service
@Slf4j
public class CustomerRegistrationService {

    private final IsvMarketplaceCustomerRepository customerRepository;
    private final IsvOnboardingService isvOnboardingService;
    private final StsClient stsClient;

    public CustomerRegistrationService(IsvMarketplaceCustomerRepository customerRepository,
                                        IsvOnboardingService isvOnboardingService,
                                        StsClient stsClient) {
        this.customerRepository = customerRepository;
        this.isvOnboardingService = isvOnboardingService;
        this.stsClient = stsClient;
    }

    /**
     * Validates the AWS Marketplace registration token and registers the buyer.
     *
     * @param productCode       ISV's product code (identifies which ISV role to assume)
     * @param registrationToken x-amzn-marketplace-token from AWS redirect
     * @return registered customer details
     */
    @Transactional
    public Map<String, Object> registerCustomer(String productCode, String registrationToken) {
        if (registrationToken == null || registrationToken.isBlank()) {
            throw new IllegalArgumentException("registrationToken is required");
        }

        IsvProduct product = isvOnboardingService.getProductEntity(productCode);
        if (!"ACTIVE".equals(product.getStatus())) {
            throw new IllegalStateException("Product is not active: " + productCode);
        }

        log.info("Resolving marketplace customer for product={}", productCode);
        StaticCredentialsProvider creds = assumeRole(product.getRoleArn(), product.getExternalId());

        ResolveCustomerResponse resolved;
        try (MarketplaceMeteringClient meteringClient = MarketplaceMeteringClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build()) {

            resolved = meteringClient.resolveCustomer(
                    ResolveCustomerRequest.builder()
                            .registrationToken(registrationToken)
                            .build());
        } catch (Exception e) {
            log.error("ResolveCustomer failed for product={}: {}", productCode, e.getMessage());
            throw new IllegalStateException("Failed to resolve customer from AWS Marketplace: " + e.getMessage(), e);
        }

        String customerIdentifier = resolved.customerIdentifier();
        String customerAwsAccountId = resolved.customerAWSAccountId();
        String resolvedProductCode = resolved.productCode();

        log.info("Resolved customer: identifier={} awsAccount={} resolvedProduct={}",
                customerIdentifier, customerAwsAccountId, resolvedProductCode);

        // Upsert — re-registration is allowed (e.g. buyer re-subscribes after cancellation)
        IsvMarketplaceCustomer customer = customerRepository
                .findByProductCodeAndCustomerIdentifier(productCode, customerIdentifier)
                .orElseGet(IsvMarketplaceCustomer::new);

        customer.setProductCode(productCode);
        customer.setCustomerIdentifier(customerIdentifier);
        customer.setCustomerAwsAccountId(customerAwsAccountId);
        customer.setStatus("ACTIVE");
        if (customer.getRegisteredAt() == null) {
            customer.setRegisteredAt(LocalDateTime.now());
        }
        customer.setCancelledAt(null);

        IsvMarketplaceCustomer saved = customerRepository.save(customer);
        log.info("Registered customer id={} for product={}", saved.getId(), productCode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customerId", customerIdentifier);
        result.put("customerAwsAccountId", customerAwsAccountId);
        result.put("productCode", productCode);
        result.put("status", "ACTIVE");
        result.put("registeredAt", saved.getRegisteredAt().toString());
        return result;
    }

    /**
     * Marks a customer as CANCELLED (e.g. when the ISV receives a subscription
     * cancellation notification from AWS SNS).
     */
    @Transactional
    public void cancelCustomer(String productCode, String customerIdentifier) {
        IsvMarketplaceCustomer customer = customerRepository
                .findByProductCodeAndCustomerIdentifier(productCode, customerIdentifier)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Customer not found: " + customerIdentifier + " for product: " + productCode));
        customer.setStatus("CANCELLED");
        customer.setCancelledAt(LocalDateTime.now());
        customerRepository.save(customer);
        log.info("Cancelled customer={} for product={}", customerIdentifier, productCode);
    }

    /**
     * Returns all customers for a product as DTOs.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCustomers(String productCode) {
        return customerRepository.findByProductCode(productCode).stream()
                .map(c -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("id", c.getId());
                    dto.put("customerIdentifier", c.getCustomerIdentifier());
                    dto.put("customerAwsAccountId", c.getCustomerAwsAccountId());
                    dto.put("status", c.getStatus());
                    dto.put("registeredAt", c.getRegisteredAt() != null ? c.getRegisteredAt().toString() : null);
                    dto.put("cancelledAt", c.getCancelledAt() != null ? c.getCancelledAt().toString() : null);
                    dto.put("lastEntitlementCheck",
                            c.getLastEntitlementCheck() != null ? c.getLastEntitlementCheck().toString() : null);
                    return dto;
                })
                .collect(Collectors.toList());
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private StaticCredentialsProvider assumeRole(String roleArn, String externalId) {
        software.amazon.awssdk.services.sts.model.AssumeRoleRequest.Builder reqBuilder =
                software.amazon.awssdk.services.sts.model.AssumeRoleRequest.builder()
                        .roleArn(roleArn)
                        .roleSessionName("xamops-metering-resolve")
                        .durationSeconds(900); // 15 min — short session for registration
        if (externalId != null && !externalId.isBlank()) {
            reqBuilder.externalId(externalId);
        }
        AssumeRoleResponse response = stsClient.assumeRole(reqBuilder.build());
        Credentials c = response.credentials();
        return StaticCredentialsProvider.create(
                AwsSessionCredentials.create(c.accessKeyId(), c.secretAccessKey(), c.sessionToken()));
    }
}
