package com.xammer.cloud.dto.cloudidr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReserveResponse {
    private String cidrId;
    private String status;
    private String message;
    private String reservationId;
    private Long expiresAt;
    private PricingInfo pricing;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PricingInfo {
        private Double totalCost;
        private Double awsPrice;
        private Double savings;
        private Double savingsPercentage;
    }
}
