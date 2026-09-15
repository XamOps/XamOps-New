package com.xammer.cloud.messaging;

/**
 * Normalises the free-text phone numbers held in {@code app_user.contact} into E.164.
 *
 * <p>That column is {@code varchar(20)} with no {@code @Pattern}, no country-code column and
 * no validation on any write path, so in practice it holds a mix of {@code 9876543210},
 * {@code 09876543210}, {@code +91 98765 43210} and {@code 919876543210}. The provider needs a
 * single canonical form, and a malformed number must be dropped locally rather than spent as
 * a billable API call that fails.
 *
 * <p>Everything here is a pure function so it is testable without a Spring context.
 */
public final class PhoneNumbers {

    /** ITU-T E.164 caps a subscriber number at 15 digits; 8 is below any real mobile. */
    private static final int MIN_DIGITS = 8;
    private static final int MAX_DIGITS = 15;

    /**
     * Shortest national subscriber number treated as sitting behind a country code. India is 10
     * digits; 9 leaves room for shorter national formats without letting a bare 10-digit mobile
     * that happens to start with "91" be mistaken for an already-international number.
     */
    private static final int MIN_SUBSCRIBER_DIGITS = 9;

    private PhoneNumbers() {
    }

    /**
     * Converts a stored contact string to {@code +<digits>}.
     *
     * @param raw                stored value, may be null, blank or punctuated
     * @param defaultCountryCode dial code to apply when {@code raw} carries none, e.g. {@code +91}
     * @return the E.164 number, or {@code null} if {@code raw} cannot be trusted as a
     *         dialable number — callers must treat null as "skip this recipient"
     */
    public static String toE164(String raw, String defaultCountryCode) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String trimmed = raw.trim();

        // Reject rather than salvage anything with letters in it. "9876543210 ext 12" and
        // "n/a" both land here, and guessing which digits are the number would be worse
        // than not sending: a wrong number is a message delivered to a stranger.
        if (trimmed.chars().anyMatch(Character::isLetter)) {
            return null;
        }

        boolean explicitPlus = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }

        String cc = normaliseCountryCode(defaultCountryCode);

        String e164;
        if (explicitPlus) {
            // Already international, punctuation aside.
            e164 = digits;
        } else if (digits.startsWith("00")) {
            // IDD prefix form, e.g. 00919876543210.
            e164 = digits.substring(2);
        } else {
            // Either national ("9876543210") or country-coded with the + never stored
            // ("919876543210"). A trunk "0" can precede either, so strip it before deciding
            // which: "(091) 9876543210" is 0 + 91 + subscriber, and prepending the dial code
            // after only stripping the 0 would yield +91919876543210.
            String stripped = digits.startsWith("0") ? digits.substring(1) : digits;
            if (stripped.isEmpty()) {
                return null;
            }
            if (cc == null) {
                // No dial code available and none supplied — cannot route this number.
                return null;
            }

            // The length test matters as much as the prefix test: a plain 10-digit Indian
            // mobile may legitimately begin with the dial code's own digits ("9198765432"),
            // and treating that as already-international would drop two subscriber digits.
            boolean alreadyCountryCoded = stripped.startsWith(cc)
                    && stripped.length() - cc.length() >= MIN_SUBSCRIBER_DIGITS;

            e164 = alreadyCountryCoded ? stripped : cc + stripped;
        }

        if (e164.length() < MIN_DIGITS || e164.length() > MAX_DIGITS) {
            return null;
        }
        return "+" + e164;
    }

    /**
     * Digits of the configured dial code, or null when unset. Accepts {@code +91}, {@code 91}
     * and {@code 0091}.
     */
    private static String normaliseCountryCode(String defaultCountryCode) {
        if (defaultCountryCode == null) {
            return null;
        }
        String cc = defaultCountryCode.replaceAll("\\D", "");
        if (cc.startsWith("00")) {
            cc = cc.substring(2);
        }
        return cc.isEmpty() ? null : cc;
    }

    /**
     * Masks a number for logging, keeping the dial code and last two digits — enough to
     * correlate a delivery failure with a user without writing a full mobile number into
     * the application log. The existing stubs logged {@code user.getContact()} verbatim.
     */
    public static String mask(String e164) {
        if (e164 == null || e164.length() < 5) {
            return "***";
        }
        return e164.substring(0, 3) + "*****" + e164.substring(e164.length() - 2);
    }
}
