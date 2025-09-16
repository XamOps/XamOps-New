package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreditDetailDto {
    private String creditName;
    private LocalDate expirationDate;
    private BigDecimal amountUsed;
    private BigDecimal amountRemaining;
}