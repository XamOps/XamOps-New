package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AwsCreditDto {
    private BigDecimal totalAmountRemaining;
    private BigDecimal totalAmountUsed;
    private List<CreditDetailDto> activeCredits;
}