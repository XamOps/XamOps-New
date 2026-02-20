package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChargeLineItemDto {
    private String description;
    private String usageQuantity;
    private String amount; // Using String to preserve formatting like "(USD 0.00)"
    private int indentationLevel; // To handle the nested descriptions

    public ChargeLineItemDto() {
    }

    public ChargeLineItemDto(String description, String usageQuantity, String amount, int indentationLevel) {
        this.description = description;
        this.usageQuantity = usageQuantity;
        this.amount = amount;
        this.indentationLevel = indentationLevel;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getUsageQuantity() { return usageQuantity; }
    public void setUsageQuantity(String usageQuantity) { this.usageQuantity = usageQuantity; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public int getIndentationLevel() { return indentationLevel; }
    public void setIndentationLevel(int indentationLevel) { this.indentationLevel = indentationLevel; }
}