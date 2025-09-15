package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceCostDetailDto {
    private String serviceName;
    private double totalCost;
    private List<RegionCostDto> regionCosts;
}