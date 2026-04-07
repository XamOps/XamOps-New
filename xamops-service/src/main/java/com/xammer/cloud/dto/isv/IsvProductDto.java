package com.xammer.cloud.dto.isv;

import com.xammer.cloud.domain.IsvProduct;
import com.xammer.cloud.domain.IsvProductDimension;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Response DTO for an ISV product. Includes the API key so the ISV
 * can configure their SDK. Dimensions are loaded separately and injected.
 */
@Data
@NoArgsConstructor
public class IsvProductDto {

    private Long id;
    private String productCode;
    private String productName;
    private String description;
    private String ownerEmail;
    private String roleArn;
    private String externalId;
    private String apiKey;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<DimensionDto> dimensions;

    /** Live stats injected by the service */
    private long activeCustomers;
    private long totalCustomers;
    private long pendingEvents;
    private long failedEvents;
    private long failedMeteringRecords;
    private long totalMeteringRecords;

    @Data
    @NoArgsConstructor
    public static class DimensionDto {
        private Long id;
        private String dimensionKey;
        private String description;
        private String unit;

        public static DimensionDto from(IsvProductDimension d) {
            DimensionDto dto = new DimensionDto();
            dto.setId(d.getId());
            dto.setDimensionKey(d.getDimensionKey());
            dto.setDescription(d.getDescription());
            dto.setUnit(d.getUnit());
            return dto;
        }
    }

    public static IsvProductDto from(IsvProduct product, List<IsvProductDimension> dimensions) {
        IsvProductDto dto = new IsvProductDto();
        dto.setId(product.getId());
        dto.setProductCode(product.getProductCode());
        dto.setProductName(product.getProductName());
        dto.setDescription(product.getDescription());
        dto.setOwnerEmail(product.getOwnerEmail());
        dto.setRoleArn(product.getRoleArn());
        dto.setExternalId(product.getExternalId());
        dto.setApiKey(product.getApiKey());
        dto.setStatus(product.getStatus());
        dto.setCreatedAt(product.getCreatedAt());
        dto.setUpdatedAt(product.getUpdatedAt());
        dto.setDimensions(dimensions.stream()
                .map(DimensionDto::from)
                .collect(Collectors.toList()));
        return dto;
    }
}
