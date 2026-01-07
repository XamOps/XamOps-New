package com.xammer.cloud.dto.cloudidr;

import lombok.Builder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstanceTypeOffering {
    private String instanceType;
    private String platform;
    private Integer quantity;
    private String primaryLocation;
    private String failOverLocation;
    private String startDate;
    private String endDate;
    private String startTime;
    private String endTime;
}
