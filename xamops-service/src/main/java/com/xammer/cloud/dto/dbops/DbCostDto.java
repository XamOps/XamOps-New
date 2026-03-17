package com.xammer.cloud.dto.dbops;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbCostDto {
    private String service;
    private String region;
    private double cost;
    private double percentOfTotal;
}
