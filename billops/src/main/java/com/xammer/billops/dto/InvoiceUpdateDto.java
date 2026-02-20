package com.xammer.billops.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * This DTO is used as the request body when an administrator
 * updates an existing draft invoice. It contains only the fields
 * that are editable on the frontend.
 */
@Data
public class InvoiceUpdateDto {

    private List<LineItemUpdateDto> lineItems;

    public List<LineItemUpdateDto> getLineItems() { return lineItems; }
    public void setLineItems(List<LineItemUpdateDto> lineItems) { this.lineItems = lineItems; }

    /**
     * Represents a single line item within the update payload.
     * Contains all the editable fields for an invoice line item.
     */
    @Data
    public static class LineItemUpdateDto {
        private String serviceName;
        private String regionName;
        private String resourceName;
        private String usageQuantity;
        private String unit;
        private BigDecimal cost;
                private boolean hidden;

        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getRegionName() { return regionName; }
        public void setRegionName(String regionName) { this.regionName = regionName; }
        public String getResourceName() { return resourceName; }
        public void setResourceName(String resourceName) { this.resourceName = resourceName; }
        public String getUsageQuantity() { return usageQuantity; }
        public void setUsageQuantity(String usageQuantity) { this.usageQuantity = usageQuantity; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public BigDecimal getCost() { return cost; }
        public void setCost(BigDecimal cost) { this.cost = cost; }
        public boolean isHidden() { return hidden; }
        public void setHidden(boolean hidden) { this.hidden = hidden; }
    }
}