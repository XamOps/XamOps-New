package com.xammer.cloud.dto.azure;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AzureBudgetDto {
    private String budgetName;
    private double budgetLimit;
    private String budgetUnit;
    private double actualSpend;
    private double forecastedSpend;

    /**
     * Whether this budget has a notification that can actually fire.
     *
     * <p>
     * Azure treats budget notifications as optional and requires at least one contact email or
     * action group on a subscription-scoped one, so it is entirely possible to have a budget that
     * tracks spend and alerts nobody. Set on creation; {@code null} on a budget read back from Azure,
     * where the list endpoint response is not inspected for notification rules.
     */
    private Boolean alertsEnabled;

    /** Non-null when something about this budget needs saying, e.g. that it cannot alert. */
    private String warning;

    /**
     * The service this budget tracks, or null for the whole subscription. The cost page matches it
     * against the billing summary's service names to show that service's spend, as it does for AWS.
     */
    private String serviceName;

    /**
     * Five-argument form kept because it is how the parser builds a budget read from Azure, where
     * neither of the fields above is known.
     */
    public AzureBudgetDto(String budgetName, double budgetLimit, String budgetUnit,
            double actualSpend, double forecastedSpend) {
        this.budgetName = budgetName;
        this.budgetLimit = budgetLimit;
        this.budgetUnit = budgetUnit;
        this.actualSpend = actualSpend;
        this.forecastedSpend = forecastedSpend;
    }
}
