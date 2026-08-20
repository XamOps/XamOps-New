package com.xammer.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This service replaced three divergent hardcoded FX constants (83.0, 84.0 and an
 * implied 83.33), so the round-trip and symmetry properties are what matter.
 */
class CurrencyConversionServiceTest {

    private static final double TOLERANCE = 1e-9;

    private CurrencyConversionService service(double rate) {
        return new CurrencyConversionService(rate, "2026-01-01");
    }

    @Test
    @DisplayName("converts USD to INR at the configured rate")
    void convertsUsdToInr() {
        assertEquals(8300.0, service(83.0).usdToInr(100.0), TOLERANCE);
    }

    @Test
    @DisplayName("converts INR back to USD at the configured rate")
    void convertsInrToUsd() {
        assertEquals(100.0, service(83.0).inrToUsd(8300.0), TOLERANCE);
    }

    @Test
    @DisplayName("round-trips without drift, so a value cannot be silently rescaled")
    void roundTripsCleanly() {
        CurrencyConversionService s = service(83.0);
        double original = 1234.56;
        assertEquals(original, s.inrToUsd(s.usdToInr(original)), 1e-6);
    }

    @Test
    @DisplayName("returns the amount unchanged when currencies match")
    void identityConversion() {
        CurrencyConversionService s = service(83.0);
        assertEquals(42.0, s.convert(42.0, "USD", "USD"), TOLERANCE);
        assertEquals(42.0, s.convert(42.0, "INR", "INR"), TOLERANCE);
    }

    @Test
    @DisplayName("treats a null or blank source currency as USD rather than failing")
    void defaultsBlankCurrencyToUsd() {
        CurrencyConversionService s = service(83.0);
        assertEquals(8300.0, s.convert(100.0, null, "INR"), TOLERANCE);
        assertEquals(8300.0, s.convert(100.0, "  ", "INR"), TOLERANCE);
    }

    @Test
    @DisplayName("is case and whitespace insensitive on currency codes")
    void normalisesCurrencyCodes() {
        assertEquals(8300.0, service(83.0).convert(100.0, " usd ", "inr"), TOLERANCE);
    }

    @Test
    @DisplayName("rejects an unsupported currency pair instead of silently returning the input")
    void rejectsUnsupportedPair() {
        CurrencyConversionService s = service(83.0);
        assertThrows(IllegalArgumentException.class, () -> s.convert(100.0, "USD", "EUR"));
    }

    @Test
    @DisplayName("refuses a non-positive rate at construction")
    void rejectsInvalidRate() {
        assertThrows(IllegalStateException.class, () -> service(0.0));
        assertThrows(IllegalStateException.class, () -> service(-1.0));
    }

    @Test
    @DisplayName("exposes the rate and its as-of date for disclosure in reports")
    void exposesRateProvenance() {
        CurrencyConversionService s = service(84.5);
        assertEquals(84.5, s.getUsdToInrRate(), TOLERANCE);
        assertEquals("2026-01-01", s.getRateAsOf());
    }
}
