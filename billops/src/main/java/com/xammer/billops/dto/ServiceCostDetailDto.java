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
    private double cost;
    private double totalCost;
    private List<RegionCostDto> regionCosts;
    private List<ResourceCostDto> resourceCosts;

    public ServiceCostDetailDto() {
    }

    public ServiceCostDetailDto(String serviceName, double cost, List<RegionCostDto> regionCosts) {
        this.serviceName = serviceName;
        this.cost = cost;
        this.totalCost = cost;
        this.regionCosts = regionCosts;
    }

    public static ServiceCostDetailDto forResources(String serviceName, double cost, List<ResourceCostDto> resourceCosts) {
        ServiceCostDetailDto dto = new ServiceCostDetailDto();
        dto.setServiceName(serviceName);
        dto.setCost(cost);
        dto.setTotalCost(cost);
        dto.setResourceCosts(resourceCosts);
        return dto;
    }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public double getCost() { return cost; }
    public void setCost(double cost) { this.cost = cost; }
    public double getTotalCost() { return totalCost; }
    public void setTotalCost(double totalCost) { this.totalCost = totalCost; }
    public List<RegionCostDto> getRegionCosts() { return regionCosts; }
    public void setRegionCosts(List<RegionCostDto> regionCosts) { this.regionCosts = regionCosts; }
    public List<ResourceCostDto> getResourceCosts() { return resourceCosts; }
    public void setResourceCosts(List<ResourceCostDto> resourceCosts) { this.resourceCosts = resourceCosts; }
}