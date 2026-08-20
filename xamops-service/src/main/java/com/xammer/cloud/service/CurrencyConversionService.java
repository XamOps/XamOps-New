package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for currency conversion across the optimization services.
 *
 * <p>Cloud provider pricing APIs quote USD. Everything computed inside the optimization
 * and waste services stays in USD; this service converts once, at the presentation
 * boundary.
 *
 * <p>It replaces a set of hardcoded constants that had drifted apart —
 * {@code USD_TO_INR_RATE = 83.0} in the GCP optimization service, {@code USD_TO_INR = 84.0}
 * in the GCP and Azure disk services, and {@code INR_TO_USD_RATE = 0.012} (an implied
 * 83.33) in the dashboard service. Those produced different costs for the same resource
 * depending on which code path a user hit.
 *
 * <p>The rate is configuration-driven via {@code currency.usd-to-inr-rate} so it can be
 * updated without a code change. It is deliberately not fetched from a live FX feed:
 * cost reports need to be reproducible, and a figure that silently moves between page
 * loads is worse than one that is explicitly stale. {@link #getRateAsOf()} carries the
 * as-of date so the UI can disclose it.
 */
@Service
public class CurrencyConversionService {

    private static final Logger logger = LoggerFactory.getLogger(CurrencyConversionService.class);

    public static final String USD = "USD";
    public static final String INR = "INR";

    private final double usdToInrRate;
    private final String rateAsOf;

    public CurrencyConversionService(
            @Value("${currency.usd-to-inr-rate:83.0}") double usdToInrRate,
            @Value("${currency.rate-as-of:2026-01-01}") String rateAsOf) {
        if (usdToInrRate <= 0) {
            throw new IllegalStateException(
                    "currency.usd-to-inr-rate must be positive, got: " + usdToInrRate);
        }
        this.usdToInrRate = usdToInrRate;
        this.rateAsOf = rateAsOf;
        logger.info("Currency conversion initialised: 1 USD = {} INR (as of {})", usdToInrRate, rateAsOf);
    }

    /** Converts a USD amount to INR. */
    public double usdToInr(double amountUsd) {
        return amountUsd * usdToInrRate;
    }

    /** Converts an INR amount to USD. */
    public double inrToUsd(double amountInr) {
        return amountInr / usdToInrRate;
    }

    /**
     * Converts an amount from one supported currency to another. Returns the amount
     * unchanged when the codes match.
     *
     * @throws IllegalArgumentException for an unsupported currency pair
     */
    public double convert(double amount, String fromCurrency, String toCurrency) {
        String from = normalise(fromCurrency);
        String to = normalise(toCurrency);
        if (from.equals(to)) {
            return amount;
        }
        if (USD.equals(from) && INR.equals(to)) {
            return usdToInr(amount);
        }
        if (INR.equals(from) && USD.equals(to)) {
            return inrToUsd(amount);
        }
        throw new IllegalArgumentException("Unsupported currency conversion: " + from + " -> " + to);
    }

    /** The configured USD-to-INR rate. */
    public double getUsdToInrRate() {
        return usdToInrRate;
    }

    /** The as-of date for the configured rate, for disclosure in reports and the UI. */
    public String getRateAsOf() {
        return rateAsOf;
    }

    private String normalise(String currency) {
        return currency == null || currency.isBlank() ? USD : currency.trim().toUpperCase();
    }
}
