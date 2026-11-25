package com.xammer.cloud.service.azure;

import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.dto.azure.AzureRightsizingRecommendation;
import com.xammer.cloud.dto.azure.AzureRightsizingResponse;
import com.xammer.cloud.dto.azure.AzureWasteItem;
import com.xammer.cloud.service.CurrencyConversionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The currency an Azure subscription is billed in, and the rate that turns a USD figure into it.
 *
 * <p>Azure spend arrives from Cost Management in the billing currency (INR for an India-billed
 * subscription), but every savings figure is computed in USD: list prices come from the Retail
 * Prices API in USD and the fallback constants are USD. The pages print those savings with ₹, so a
 * $13.14 saving read as ₹13.14 — about 95 times too small next to the INR spend above it.
 *
 * <p>Savings stay in USD inside the services, because cross-provider totals (BillOps client savings)
 * sum the stored snapshots in USD. They are converted here, at the Azure presentation boundary.
 *
 * <p>The rate is Azure's own: the subscription's billing-currency cost for a month divided by the
 * USD equivalent Cost Management reports for the same month. That is the conversion Azure applied
 * to this subscription's invoice, so a converted saving is comparable with the spend shown next to
 * it. The configured {@code currency.usd-to-inr-rate} is used only for an INR subscription whose USD
 * series has not been ingested yet. Any other currency with no Azure rate stays in USD and is
 * labelled USD, rather than converted with a guessed rate.
 */
@Service
public class AzureBillingCurrencyService {

    /** A month needs at least this much USD spend before its ratio is trusted as a rate. */
    private static final double MIN_USD_FOR_RATE = 1.0;

    private final AzureBillingDataIngestionService ingestionService;
    private final CurrencyConversionService currencyConversionService;

    public AzureBillingCurrencyService(AzureBillingDataIngestionService ingestionService,
                                       CurrencyConversionService currencyConversionService) {
        this.ingestionService = ingestionService;
        this.currencyConversionService = currencyConversionService;
    }

    /**
     * A billing currency and its units per US dollar.
     *
     * @param code        ISO-4217 code the converted figures are in
     * @param unitsPerUsd how many units of {@code code} one US dollar is worth
     */
    public record BillingCurrency(String code, double unitsPerUsd) {

        public static final BillingCurrency USD = new BillingCurrency(CurrencyConversionService.USD, 1.0);

        /** Converts a USD amount into this currency. */
        public double fromUsd(double amountUsd) {
            return amountUsd * unitsPerUsd;
        }

        /** Converts an amount in this currency into USD. */
        public double toUsd(double amount) {
            return amount / unitsPerUsd;
        }

        /** True when {@code currencyCode} names this currency. */
        public boolean is(String currencyCode) {
            return currencyCode != null && code.equalsIgnoreCase(currencyCode.trim());
        }

        /**
         * Converts an amount stated in {@code fromCurrency} into this currency. Amounts already in
         * this currency are returned as-is; USD amounts are converted; anything else is returned
         * unchanged, and the caller should keep its original code.
         */
        public double convertFrom(double amount, String fromCurrency) {
            if (fromCurrency == null || is(fromCurrency)) {
                return amount;
            }
            if (CurrencyConversionService.USD.equalsIgnoreCase(fromCurrency.trim())) {
                return fromUsd(amount);
            }
            return amount;
        }

        /** True when {@link #convertFrom} can express an amount in {@code fromCurrency} in this currency. */
        public boolean canConvertFrom(String fromCurrency) {
            return fromCurrency == null || is(fromCurrency)
                    || CurrencyConversionService.USD.equalsIgnoreCase(fromCurrency.trim());
        }
    }

    /**
     * Copies of the waste findings with their USD savings expressed in {@code currency}. The
     * originals are left alone: they are what the waste cache and its DB snapshot hold, in USD.
     */
    public List<AzureWasteItem> toBillingCurrency(List<AzureWasteItem> items, BillingCurrency currency) {
        if (items == null) {
            return null;
        }
        List<AzureWasteItem> converted = new ArrayList<>(items.size());
        for (AzureWasteItem item : items) {
            converted.add(toBillingCurrency(item, currency));
        }
        return converted;
    }

    private AzureWasteItem toBillingCurrency(AzureWasteItem item, BillingCurrency currency) {
        AzureWasteItem copy = new AzureWasteItem(item.getResourceId(), item.getResourceName(),
                item.getResourceType(), item.getRegion(), item.getReason(), item.getMonthlySavings());
        copy.setSavingsUnknown(item.isSavingsUnknown());
        copy.setNoCostImpact(item.isNoCostImpact());
        copy.setCurrency(item.getCurrency());
        if (currency.canConvertFrom(item.getCurrency())) {
            copy.setMonthlySavings(round2(currency.convertFrom(item.getMonthlySavings(), item.getCurrency())));
            copy.setCurrency(currency.code());
            copy.setReason(restateUsdAmounts(item.getReason(), currency));
        }
        return copy;
    }

    /**
     * A copy of a rightsizing response with every USD saving expressed in {@code currency}.
     * The status and every non-money field are carried over unchanged.
     */
    public AzureRightsizingResponse toBillingCurrency(AzureRightsizingResponse response, BillingCurrency currency) {
        if (response == null) {
            return null;
        }
        List<AzureRightsizingRecommendation> recs = response.getRecommendations();
        if (recs == null) {
            return new AzureRightsizingResponse(response.getStatus(), null);
        }
        List<AzureRightsizingRecommendation> converted = new ArrayList<>(recs.size());
        for (AzureRightsizingRecommendation rec : recs) {
            AzureRightsizingRecommendation copy = copyOf(rec);
            if (currency.canConvertFrom(rec.getCurrency())) {
                copy.setMonthlySavings(round2(currency.convertFrom(rec.getMonthlySavings(), rec.getCurrency())));
                copy.setAnnualSavings(round2(currency.convertFrom(rec.getAnnualSavings(), rec.getCurrency())));
                copy.setCurrency(currency.code());
                copy.setProblem(restateUsdAmounts(rec.getProblem(), currency));
                copy.setSolution(restateUsdAmounts(rec.getSolution(), currency));
            }
            converted.add(copy);
        }
        return new AzureRightsizingResponse(response.getStatus(), converted);
    }

    private static AzureRightsizingRecommendation copyOf(AzureRightsizingRecommendation rec) {
        AzureRightsizingRecommendation copy = new AzureRightsizingRecommendation();
        copy.setRecommendationId(rec.getRecommendationId());
        copy.setResourceId(rec.getResourceId());
        copy.setResourceName(rec.getResourceName());
        copy.setResourceType(rec.getResourceType());
        copy.setCategory(rec.getCategory());
        copy.setImpact(rec.getImpact());
        copy.setCurrentSku(rec.getCurrentSku());
        copy.setTargetSku(rec.getTargetSku());
        copy.setMonthlySavings(rec.getMonthlySavings());
        copy.setAnnualSavings(rec.getAnnualSavings());
        copy.setCurrency(rec.getCurrency());
        copy.setProblem(rec.getProblem());
        copy.setSolution(rec.getSolution());
        copy.setMaxCpuObserved(rec.getMaxCpuObserved());
        copy.setAvgCpuObserved(rec.getAvgCpuObserved());
        copy.setMaxMemoryObserved(rec.getMaxMemoryObserved());
        copy.setSavingsUnknown(rec.isSavingsUnknown());
        copy.setLocation(rec.getLocation());
        return copy;
    }

    /** "$16.35" and "16.35 USD" amounts in finding text, restated in the billing currency. */
    private static final Pattern DOLLAR_AMOUNT = Pattern.compile("\\$\\s?([0-9][0-9,]*(?:\\.[0-9]+)?)");
    private static final Pattern USD_AMOUNT = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s?USD\\b");

    /**
     * Rewrites the USD amounts the detectors embed in their explanations ("about $16.35/month"), so
     * the text agrees with the converted figure beside it instead of quoting dollars on a ₹ page.
     */
    static String restateUsdAmounts(String text, BillingCurrency currency) {
        if (text == null || text.isEmpty() || CurrencyConversionService.USD.equals(currency.code())) {
            return text;
        }
        String out = replaceAmounts(text, DOLLAR_AMOUNT, currency);
        return replaceAmounts(out, USD_AMOUNT, currency);
    }

    private static String replaceAmounts(String text, Pattern pattern, BillingCurrency currency) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String formatted;
            try {
                double usd = Double.parseDouble(m.group(1).replace(",", ""));
                formatted = formatAmount(currency.fromUsd(usd), currency.code());
            } catch (NumberFormatException e) {
                formatted = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(formatted));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** "₹1,557" for INR; "EUR 14.20" for any other code. */
    static String formatAmount(double amount, String code) {
        if (CurrencyConversionService.INR.equals(code)) {
            return "₹" + String.format(Locale.ROOT, "%,.0f", amount);
        }
        return code + " " + String.format(Locale.ROOT, "%,.2f", amount);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** The billing currency for a subscription, with the rate to convert USD figures into it. */
    public BillingCurrency forSubscription(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return BillingCurrency.USD;
        }
        String code = ingestionService.readCurrency(subscriptionId)
                .map(c -> c.trim().toUpperCase(Locale.ROOT))
                .filter(c -> !c.isEmpty())
                .orElse(null);
        if (code == null || CurrencyConversionService.USD.equals(code)) {
            return BillingCurrency.USD;
        }
        OptionalDouble azureRate = azureRate(subscriptionId);
        if (azureRate.isPresent()) {
            return new BillingCurrency(code, azureRate.getAsDouble());
        }
        if (CurrencyConversionService.INR.equals(code)) {
            return new BillingCurrency(code, currencyConversionService.getUsdToInrRate());
        }
        return BillingCurrency.USD;
    }

    /**
     * Azure's rate for the most recent actual month with enough USD spend to divide by: the month's
     * billing-currency total from the cost history over its USD total from Cost Management.
     */
    private OptionalDouble azureRate(String subscriptionId) {
        Optional<AzureDashboardData.CostHistory> history = ingestionService.readCostHistory(subscriptionId);
        Optional<Map<String, Double>> usdByMonth = ingestionService.readCostUsdByMonth(subscriptionId);
        if (history.isEmpty() || usdByMonth.isEmpty()) {
            return OptionalDouble.empty();
        }
        List<String> labels = history.get().getLabels();
        List<Double> costs = history.get().getCosts();
        List<Boolean> forecast = history.get().getForecast();
        if (labels == null || costs == null) {
            return OptionalDouble.empty();
        }
        for (int i = Math.min(labels.size(), costs.size()) - 1; i >= 0; i--) {
            boolean isForecast = forecast != null && i < forecast.size() && Boolean.TRUE.equals(forecast.get(i));
            if (isForecast) {
                continue;
            }
            Double local = costs.get(i);
            Double usd = usdByMonth.get().get(labels.get(i));
            if (local != null && usd != null && usd >= MIN_USD_FOR_RATE && local > 0) {
                return OptionalDouble.of(local / usd);
            }
        }
        return OptionalDouble.empty();
    }
}
