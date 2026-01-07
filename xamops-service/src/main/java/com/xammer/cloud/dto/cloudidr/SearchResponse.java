package com.xammer.cloud.dto.cloudidr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {
    private Boolean available;
    private List<InstanceTypeOffering> instanceTypeOfferings;
    private String message;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstanceTypeOffering {
        private String instanceType;
        private String platform;
        private Integer quantity;
        private String availabilityZone;
        private String startDate;
        private String endDate;
        private String startTime;
        private String endTime;
        private Double hourlyRate;
        private Double totalCost;
        private Double awsPrice;
        private Double savings;
        private String offeringId;
    }
}
