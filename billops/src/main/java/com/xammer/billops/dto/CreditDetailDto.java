package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreditDetailDto {
    private String creditName;
    private LocalDate expirationDate;
    private BigDecimal amountUsed;
    private BigDecimal amountRemaining;

    

    public String getCreditName() { return creditName; }
    public void setCreditName(String creditName) { this.creditName = creditName; }
    public LocalDate getExpirationDate() { return expirationDate; }
    public void setExpirationDate(LocalDate expirationDate) { this.expirationDate = expirationDate; }
    public BigDecimal getAmountUsed() { return amountUsed; }
    public void setAmountUsed(BigDecimal amountUsed) { this.amountUsed = amountUsed; }
    public BigDecimal getAmountRemaining() { return amountRemaining; }
    public void setAmountRemaining(BigDecimal amountRemaining) { this.amountRemaining = amountRemaining; }
}