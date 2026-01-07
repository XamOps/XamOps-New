package com.xammer.cloud.dto.cloudidr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReserveRequest {
    private String accountId;
    private String policyName;
    private String instanceType;
    private String platform;
    private Integer quantity;
    private String startDate;
    private String endDate;
    private String startTime;
    private String endTime;
    private String region;
    private String availabilityZone;
    private String offeringId;
}
