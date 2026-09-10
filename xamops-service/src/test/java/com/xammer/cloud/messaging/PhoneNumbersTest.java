package com.xammer.cloud.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one place a mistake sends a customer's alert to a stranger.
 *
 * <p>{@code app_user.contact} is unvalidated free text, so the range of stored values here is not
 * hypothetical — bare 10-digit, trunk-prefixed, country-coded-without-plus and punctuated forms
 * all exist in the column.
 */
class PhoneNumbersTest {

    private static final String IN = "+91";

    @ParameterizedTest
    @CsvSource({
            // bare national number — the most common stored form
            "9876543210,          +919876543210",
            // trunk prefix must be dropped, not carried into the E.164 number
            "09876543210,         +919876543210",
            // country code present but the + was never stored
            "919876543210,        +919876543210",
            // already correct
            "+919876543210,       +919876543210",
            // punctuation and spacing
            "'+91 98765 43210',   +919876543210",
            "'+91-98765-43210',   +919876543210",
            // IDD prefix form
            "00919876543210,      +919876543210",
            // trunk prefix in front of an already-country-coded number: 0 + 91 + subscriber.
            // Stripping only the 0 and prepending the dial code would give +91919876543210.
            "'(091) 9876543210',  +919876543210",
            // a bare 10-digit mobile that happens to start with the dial code's own digits.
            // Treating this as already-international would silently lose two digits.
            "9198765432,          +919198765432",
            // a non-Indian number must survive untouched
            "+14155552671,        +14155552671"
    })
    void normalisesStoredContactFormsToE164(String stored, String expected) {
        assertThat(PhoneNumbers.toE164(stored, IN)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9876543210 ext 12",  // letters — salvaging digits could dial the wrong person
            "n/a",
            "not provided",
            "12345",              // too short to be a real mobile
            "9876543210987654321" // longer than E.164 permits
    })
    void rejectsValuesThatCannotBeTrustedAsNumbers(String stored) {
        assertThat(PhoneNumbers.toE164(stored, IN)).isNull();
    }

    @Test
    void returnsNullForAbsentContact() {
        assertThat(PhoneNumbers.toE164(null, IN)).isNull();
        assertThat(PhoneNumbers.toE164("", IN)).isNull();
        assertThat(PhoneNumbers.toE164("   ", IN)).isNull();
    }

    @Test
    void nationalNumberIsUnroutableWithoutAConfiguredCountryCode() {
        assertThat(PhoneNumbers.toE164("9876543210", null)).isNull();
        assertThat(PhoneNumbers.toE164("9876543210", "")).isNull();
    }

    @Test
    void internationalNumberStillWorksWithoutAConfiguredCountryCode() {
        assertThat(PhoneNumbers.toE164("+919876543210", null)).isEqualTo("+919876543210");
    }

    @Test
    void maskKeepsDialCodeAndLastTwoDigitsOnly() {
        // The previous stubs logged the raw contact; the middle digits must not reach a log.
        assertThat(PhoneNumbers.mask("+919876543210")).isEqualTo("+91*****10");
        assertThat(PhoneNumbers.mask(null)).isEqualTo("***");
    }
}
