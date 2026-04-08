package com.xammer.cloud.service;

import com.xammer.cloud.domain.IsvProduct;
import com.xammer.cloud.domain.IsvProductDimension;
import com.xammer.cloud.dto.isv.IsvProductDto;
import com.xammer.cloud.dto.isv.IsvProductRequest;
import com.xammer.cloud.repository.IsvMarketplaceCustomerRepository;
import com.xammer.cloud.repository.IsvProductDimensionRepository;
import com.xammer.cloud.repository.IsvProductRepository;
import com.xammer.cloud.repository.MeteringRecordRepository;
import com.xammer.cloud.repository.UsageEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages ISV product registration, API key lifecycle, and product configuration.
 * Each ISV product corresponds to exactly one AWS Marketplace SaaS product listing.
 */
@Service
@Slf4j
public class IsvOnboardingService {

    private static final int API_KEY_BYTES = 24; // 48 hex chars → "xmp_" + 48 = 52 chars total
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final IsvProductRepository productRepository;
    private final IsvProductDimensionRepository dimensionRepository;
    private final IsvMarketplaceCustomerRepository customerRepository;
    private final UsageEventRepository usageEventRepository;
    private final MeteringRecordRepository meteringRecordRepository;

    public IsvOnboardingService(IsvProductRepository productRepository,
                                 IsvProductDimensionRepository dimensionRepository,
                                 IsvMarketplaceCustomerRepository customerRepository,
                                 UsageEventRepository usageEventRepository,
                                 MeteringRecordRepository meteringRecordRepository) {
        this.productRepository = productRepository;
        this.dimensionRepository = dimensionRepository;
        this.customerRepository = customerRepository;
        this.usageEventRepository = usageEventRepository;
        this.meteringRecordRepository = meteringRecordRepository;
    }

    // ─── Product Registration ─────────────────────────────────────────────────

    @Transactional
    public IsvProductDto registerProduct(IsvProductRequest request) {
        if (productRepository.existsByProductCode(request.getProductCode())) {
            throw new IllegalArgumentException(
                    "Product already registered: " + request.getProductCode());
        }
        validateRequest(request);

        IsvProduct product = new IsvProduct();
        product.setProductCode(request.getProductCode().trim());
        product.setProductName(request.getProductName().trim());
        product.setDescription(request.getDescription());
        product.setOwnerEmail(request.getOwnerEmail().trim().toLowerCase());
        product.setRoleArn(request.getRoleArn().trim());
        product.setExternalId(request.getExternalId());
        product.setApiKey(generateApiKey());
        product.setStatus("ACTIVE");

        IsvProduct saved = productRepository.save(product);
        log.info("Registered ISV product: code={} owner={}", saved.getProductCode(), saved.getOwnerEmail());

        List<IsvProductDimension> dimensions = saveDimensions(saved.getProductCode(), request);
        return buildDto(saved, dimensions);
    }

    @Transactional
    public IsvProductDto updateProduct(String productCode, IsvProductRequest request) {
        IsvProduct product = getProductEntity(productCode);
        validateRequest(request);

        if (request.getProductName() != null) product.setProductName(request.getProductName().trim());
        if (request.getDescription() != null) product.setDescription(request.getDescription());
        if (request.getOwnerEmail() != null) product.setOwnerEmail(request.getOwnerEmail().trim().toLowerCase());
        if (request.getRoleArn() != null) product.setRoleArn(request.getRoleArn().trim());
        if (request.getExternalId() != null) product.setExternalId(request.getExternalId());
        product.setUpdatedAt(LocalDateTime.now());

        IsvProduct saved = productRepository.save(product);

        // Replace all dimensions if provided
        if (request.getDimensions() != null && !request.getDimensions().isEmpty()) {
            dimensionRepository.deleteByProductCode(productCode);
            saveDimensions(productCode, request);
        }

        List<IsvProductDimension> dims = dimensionRepository.findByProductCode(productCode);
        log.info("Updated ISV product: code={}", productCode);
        return buildDto(saved, dims);
    }

    @Transactional
    public IsvProductDto deactivateProduct(String productCode) {
        IsvProduct product = getProductEntity(productCode);
        product.setStatus("INACTIVE");
        product.setUpdatedAt(LocalDateTime.now());
        IsvProduct saved = productRepository.save(product);
        log.info("Deactivated ISV product: code={}", productCode);
        List<IsvProductDimension> dims = dimensionRepository.findByProductCode(productCode);
        return buildDto(saved, dims);
    }

    @Transactional
    public IsvProductDto regenerateApiKey(String productCode) {
        IsvProduct product = getProductEntity(productCode);
        product.setApiKey(generateApiKey());
        product.setUpdatedAt(LocalDateTime.now());
        IsvProduct saved = productRepository.save(product);
        log.info("Regenerated API key for ISV product: code={}", productCode);
        List<IsvProductDimension> dims = dimensionRepository.findByProductCode(productCode);
        return buildDto(saved, dims);
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public IsvProductDto getProduct(String productCode) {
        IsvProduct product = getProductEntity(productCode);
        List<IsvProductDimension> dims = dimensionRepository.findByProductCode(productCode);
        return buildDto(product, dims);
    }

    @Transactional(readOnly = true)
    public List<IsvProductDto> getAllProducts() {
        return productRepository.findAll().stream()
                .map(p -> buildDto(p, dimensionRepository.findByProductCode(p.getProductCode())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IsvProductDto> getProductsByOwner(String ownerEmail) {
        return productRepository.findByOwnerEmail(ownerEmail.toLowerCase()).stream()
                .map(p -> buildDto(p, dimensionRepository.findByProductCode(p.getProductCode())))
                .collect(Collectors.toList());
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    public IsvProduct getProductEntity(String productCode) {
        return productRepository.findByProductCode(productCode)
                .orElseThrow(() -> new IllegalArgumentException("ISV product not found: " + productCode));
    }

    public IsvProduct getProductByApiKey(String apiKey) {
        return productRepository.findByApiKey(apiKey)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
    }

    private List<IsvProductDimension> saveDimensions(String productCode, IsvProductRequest request) {
        if (request.getDimensions() == null || request.getDimensions().isEmpty()) {
            return List.of();
        }
        List<IsvProductDimension> dims = request.getDimensions().stream().map(d -> {
            IsvProductDimension dim = new IsvProductDimension();
            dim.setProductCode(productCode);
            dim.setDimensionKey(d.getDimensionKey().trim().toLowerCase());
            dim.setDescription(d.getDescription());
            dim.setUnit(d.getUnit());
            return dim;
        }).collect(Collectors.toList());
        return dimensionRepository.saveAll(dims);
    }

    private IsvProductDto buildDto(IsvProduct product, List<IsvProductDimension> dimensions) {
        IsvProductDto dto = IsvProductDto.from(product, dimensions);
        dto.setActiveCustomers(customerRepository.countActiveByProductCode(product.getProductCode()));
        dto.setTotalCustomers(customerRepository.countTotalByProductCode(product.getProductCode()));
        dto.setPendingEvents(usageEventRepository.countPendingByProductCode(product.getProductCode()));
        dto.setFailedEvents(usageEventRepository.countFailedByProductCode(product.getProductCode()));
        dto.setFailedMeteringRecords(meteringRecordRepository.countFailedByProductCode(product.getProductCode()));
        dto.setTotalMeteringRecords(meteringRecordRepository.countTotalByProductCode(product.getProductCode()));
        return dto;
    }

    private void validateRequest(IsvProductRequest request) {
        if (request.getProductCode() == null || request.getProductCode().isBlank()) {
            throw new IllegalArgumentException("productCode is required");
        }
        if (request.getProductName() == null || request.getProductName().isBlank()) {
            throw new IllegalArgumentException("productName is required");
        }
        if (request.getOwnerEmail() == null || request.getOwnerEmail().isBlank()) {
            throw new IllegalArgumentException("ownerEmail is required");
        }
        if (request.getRoleArn() == null || request.getRoleArn().isBlank()) {
            throw new IllegalArgumentException("roleArn is required");
        }
    }

    private String generateApiKey() {
        byte[] bytes = new byte[API_KEY_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return "xmp_" + HexFormat.of().formatHex(bytes);
    }
}
