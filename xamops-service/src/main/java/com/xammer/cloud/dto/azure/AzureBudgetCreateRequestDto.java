package com.xammer.cloud.dto.azure;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AzureBudgetCreateRequestDto {
    private String budgetName;
    private double budgetLimit;
    private String budgetUnit;

    /**
     * Addresses to alert when the budget is exceeded. Optional.
     *
     * <p>
     * Without at least one of these the created budget cannot notify anyone: Azure requires a
     * contact email or action group on a subscription-scoped budget notification, and
     * {@code contactRoles} on its own is not accepted. Budgets were previously always created with
     * no notifications at all, so every one of them silently tracked spend and never alerted.
     */
    private List<String> contactEmails;
}
