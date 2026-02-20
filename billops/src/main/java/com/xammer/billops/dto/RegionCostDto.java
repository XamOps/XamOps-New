package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegionCostDto {
    private String region;
    private String regionName;
    private double cost;
    private List<ResourceCostDto> resources;

    public RegionCostDto() {
    }

    public RegionCostDto(String region, double cost) {
        this.region = region;
        this.regionName = region;
        this.cost = cost;
    }

    public RegionCostDto(String region, double cost, List<ResourceCostDto> resources) {
        this.region = region;
        this.regionName = region;
        this.cost = cost;
        this.resources = resources;
    }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
    public double getCost() { return cost; }
    public void setCost(double cost) { this.cost = cost; }
    public List<ResourceCostDto> getResources() { return resources; }
    public void setResources(List<ResourceCostDto> resources) { this.resources = resources; }
}