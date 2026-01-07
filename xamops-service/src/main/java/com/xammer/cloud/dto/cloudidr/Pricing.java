package com.xammer.cloud.dto.cloudidr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Pricing {
    private Double ourPrice;
    private Double cspProviderPrice;
    private Double savings;
    private Double savingsPercentage;
}
