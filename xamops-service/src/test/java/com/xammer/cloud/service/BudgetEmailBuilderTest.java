package com.xammer.cloud.service;

import com.xammer.cloud.dto.DashboardData.BudgetDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BudgetEmailBuilder.
 *
 * Verifies that all email variants contain the correct content, amounts,
 * currency symbols, and are protected against XSS via HTML escaping.
 * No Spring context needed — pure string generation.
 */
class BudgetEmailBuilderTest {

    private BudgetEmailBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new BudgetEmailBuilder();
    }

    // ── buildBudgetCreatedEmail ───────────────────────────────────────────────

    @Test
    void createdEmail_containsXamOpsBranding() {
        String html = builder.buildBudgetCreatedEmail(budget("Prod-Budget", 10000, 8000, 9500), "Acme Corp", "123456789012");

        assertThat(html).contains("XAMOPS");
        assertThat(html).contains("Budget Created");
    }

    @Test
    void createdEmail_containsBudgetNameAndAccountName() {
        String html = builder.buildBudgetCreatedEmail(budget("Prod-Budget", 10000, 8000, 9500), "Acme Corp", "123456789012");

        assertThat(html).contains("Prod-Budget");
        assertThat(html).contains("Acme Corp");
        assertThat(html).contains("123456789012");
    }

    @Test
    void createdEmail_containsBudgetLimit() {
        String html = builder.buildBudgetCreatedEmail(budget("Test-Budget", 5000, 0, 0), "Test Co", "999");

        assertThat(html).contains("5000");
    }

    @Test
    void createdEmail_mentionsThresholdPercentages() {
        String html = builder.buildBudgetCreatedEmail(budget("B", 1000, 0, 0), "Acme", "123");

        assertThat(html).contains("50%");
        assertThat(html).contains("80%");
        assertThat(html).contains("100%");
    }

    // ── buildBudgetThresholdEmail — actual spend ──────────────────────────────

    @Test
    void thresholdEmail_50pct_actualSpend_containsSpentLabel() {
        BudgetDetails b = budget("B", 10000, 5000, 6000);
        String html = builder.buildBudgetThresholdEmail(b, "Acme", "123", 50, false);

        // Actual spend path — should not say "Forecasted:"
        assertThat(html).contains("50%");
        assertThat(html).doesNotContain("Forecasted:");
    }

    @Test
    void thresholdEmail_80pct_contains80pctLabel() {
        BudgetDetails b = budget("B", 10000, 8200, 9000);
        String html = builder.buildBudgetThresholdEmail(b, "Acme", "123", 80, false);
        assertThat(html).contains("80%");
        // BigDecimal.toPlainString() produces "8200.00" (no thousands separator)
        assertThat(html).contains("8200");
    }

    @Test
    void thresholdEmail_100pct_containsExceededLabel() {
        BudgetDetails b = budget("B", 10000, 10500, 11000);
        String html = builder.buildBudgetThresholdEmail(b, "Acme", "123", 100, false);

        assertThat(html).contains("Budget Exceeded");
    }

    // ── buildBudgetThresholdEmail — forecasted ────────────────────────────────

    @Test
    void thresholdEmail_forecasted_containsForecastLabel() {
        BudgetDetails b = budget("B", 10000, 5000, 12000);
        String html = builder.buildBudgetThresholdEmail(b, "Acme", "123", 100, true);

        assertThat(html).containsIgnoringCase("Forecast");
    }

    @Test
    void thresholdEmail_forecastedAmount_shownInEmail() {
        BudgetDetails b = budget("B", 10000, 5000, 12000);
        String html = builder.buildBudgetThresholdEmail(b, "Acme", "123", 100, true);
        // BigDecimal.toPlainString() produces "12000.00"
        assertThat(html).contains("12000");
    }

    // ── Currency symbols ──────────────────────────────────────────────────────

    @Test
    void usdBudget_showsDollarSign() {
        String html = builder.buildBudgetThresholdEmail(
                budgetWithCurrency("B", 1000, 500, 600, "USD"), "Acme", "123", 50, false);

        assertThat(html).contains("$");
    }

    @Test
    void inrBudget_showsRupeeSymbol() {
        String html = builder.buildBudgetThresholdEmail(
                budgetWithCurrency("B", 100000, 50000, 60000, "INR"), "Acme", "123", 50, false);
        // BudgetEmailBuilder uses HTML entity &#8377; for INR
        assertThat(html).contains("&#8377;");
    }

    @Test
    void eurBudget_showsEuroSymbol() {
        String html = builder.buildBudgetThresholdEmail(
                budgetWithCurrency("B", 5000, 2500, 3000, "EUR"), "Acme", "123", 50, false);
        // BudgetEmailBuilder uses HTML entity &euro; for EUR
        assertThat(html).contains("&euro;");
    }

    @Test
    void gbpBudget_showsPoundSign() {
        String html = builder.buildBudgetThresholdEmail(
                budgetWithCurrency("B", 5000, 2500, 3000, "GBP"), "Acme", "123", 50, false);
        // BudgetEmailBuilder uses HTML entity &pound; for GBP
        assertThat(html).contains("&pound;");
    }

    // ── XSS protection ────────────────────────────────────────────────────────

    @Test
    void xss_budgetNameWithScriptTag_isEscaped() {
        BudgetDetails evil = budgetWithName("<script>alert('xss')</script>");
        String html = builder.buildBudgetCreatedEmail(evil, "Acme", "123");

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void xss_accountNameWithHtml_isEscaped() {
        BudgetDetails b = budget("Budget", 1000, 500, 600);
        String html = builder.buildBudgetCreatedEmail(b, "<b>Hacker & Co</b>", "123");

        assertThat(html).doesNotContain("<b>");
        assertThat(html).contains("&amp;");
    }

    @Test
    void xss_accountIdWithHtml_isEscaped() {
        BudgetDetails b = budget("Budget", 1000, 500, 600);
        String html = builder.buildBudgetCreatedEmail(b, "Acme", "<img src=x>");

        assertThat(html).doesNotContain("<img");
    }

    // ── GCP budget email ──────────────────────────────────────────────────────

    @Test
    void gcpCreatedEmail_containsGcpBranding() {
        String html = builder.buildGcpBudgetCreatedEmail("gcp-budget", 50000, "USD", "my-project");

        assertThat(html).contains("GCP Budget Created");
        assertThat(html).contains("my-project");
        assertThat(html).contains("50000.00");
    }

    @Test
    void gcpCreatedEmail_mentions4Thresholds() {
        String html = builder.buildGcpBudgetCreatedEmail("gcp-budget", 50000, "USD", "my-project");

        assertThat(html).contains("90%");  // GCP has 90% in addition to 50/80/100
    }

    // ── Azure budget email ────────────────────────────────────────────────────

    @Test
    void azureCreatedEmail_containsAzureBranding() {
        String html = builder.buildAzureBudgetCreatedEmail("azure-budget", 20000, "USD", "sub-abc-123");

        assertThat(html).contains("Azure Budget Created");
        assertThat(html).contains("sub-abc-123");
    }

    // ── Valid HTML structure ──────────────────────────────────────────────────

    @Test
    void createdEmail_isValidHtmlDocument() {
        String html = builder.buildBudgetCreatedEmail(budget("B", 1000, 500, 600), "Acme", "123");

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("</html>");
        assertThat(html).contains("<body");
        assertThat(html).contains("</body>");
    }

    @Test
    void thresholdEmail_isValidHtmlDocument() {
        String html = builder.buildBudgetThresholdEmail(budget("B", 1000, 500, 600), "Acme", "123", 80, false);

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("</html>");
    }

    @Test
    void allEmails_containFooterCopyright() {
        String html = builder.buildBudgetCreatedEmail(budget("B", 1000, 500, 600), "Acme", "123");

        assertThat(html).containsIgnoringCase("XamOps");
        assertThat(html).contains("All rights reserved");
    }

    // ── Null safety ───────────────────────────────────────────────────────────

    @Test
    void nullActualSpend_doesNotThrow() {
        BudgetDetails b = new BudgetDetails("B", BigDecimal.valueOf(1000), "USD", null, BigDecimal.valueOf(1200));

        // Should not throw NullPointerException
        assertThat(builder.buildBudgetThresholdEmail(b, "Acme", "123", 100, false))
                .isNotBlank();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BudgetDetails budget(String name, double limit, double actual, double forecasted) {
        return new BudgetDetails(name,
                BigDecimal.valueOf(limit), "USD",
                BigDecimal.valueOf(actual), BigDecimal.valueOf(forecasted));
    }

    private BudgetDetails budgetWithCurrency(String name, double limit, double actual, double forecasted, String currency) {
        return new BudgetDetails(name,
                BigDecimal.valueOf(limit), currency,
                BigDecimal.valueOf(actual), BigDecimal.valueOf(forecasted));
    }

    private BudgetDetails budgetWithName(String name) {
        return budget(name, 1000, 500, 600);
    }
}
