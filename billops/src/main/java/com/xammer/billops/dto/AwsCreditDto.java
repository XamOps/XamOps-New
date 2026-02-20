package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AwsCreditDto {
    private BigDecimal totalCredit;
    private BigDecimal remainingCredit;
    private List<CreditDetailDto> creditDetails;

    public AwsCreditDto() {
    }

    public AwsCreditDto(BigDecimal totalCredit, BigDecimal remainingCredit, List<CreditDetailDto> creditDetails) {
        this.totalCredit = totalCredit;
        this.remainingCredit = remainingCredit;
        this.creditDetails = creditDetails;
    }

    public BigDecimal getTotalCredit() { return totalCredit; }
    public void setTotalCredit(BigDecimal totalCredit) { this.totalCredit = totalCredit; }
    public BigDecimal getRemainingCredit() { return remainingCredit; }
    public void setRemainingCredit(BigDecimal remainingCredit) { this.remainingCredit = remainingCredit; }
    public List<CreditDetailDto> getCreditDetails() { return creditDetails; }
    public void setCreditDetails(List<CreditDetailDto> creditDetails) { this.creditDetails = creditDetails; }
}