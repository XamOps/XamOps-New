package com.xammer.cloud.service.autospotting.ledger.money;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount and its currency (design-ledger.md §9.2, FR-LED-17, AC-LED-08). Decimal only: the amount
 * is a {@link BigDecimal}, never a {@code double}, and arithmetic across two currencies throws
 * {@link CurrencyMismatchException}. Rounding is explicit and only where design-ledger.md §9.1 sets
 * it:
 * <ul>
 *   <li>{@link #plus}, {@link #minus}, {@link #times}, {@link #negate} are exact (no rounding);</li>
 *   <li>{@link #ofRateSeconds} is the one division, by 3600, to scale {@value #ENTRY_SCALE},
 *       {@code HALF_UP}, applied once to a sum of rate-times-seconds products;</li>
 *   <li>{@link #toMinorUnit} rounds once, {@code HALF_UP}, to the currency's minor unit, for a
 *       statement line.</li>
 * </ul>
 * Equality compares the numeric value ({@code compareTo}), so {@code 1.50} and {@code 1.5} are equal
 * (design-ledger.md §9.1: comparisons use {@code compareTo}, not {@code equals}).
 *
 * @param amount   the amount, not null
 * @param currency the ISO 4217 currency, not null
 */
@JsonSerialize(using = MoneyJson.Serializer.class)
@JsonDeserialize(using = MoneyJson.Deserializer.class)
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    /** Scale of an entry amount: {@code NUMERIC(24,10)}. */
    public static final int ENTRY_SCALE = 10;

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600L);

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    /** Parses {@code amount} from its decimal string (never through a double) with an ISO currency code. */
    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /** Multiplies by a factor, exactly (the scale grows; nothing is rounded). */
    public Money times(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        return new Money(amount.multiply(factor), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /**
     * The one division of the accrual maths: a sum of {@code rate x seconds} products divided by 3600
     * to scale {@value #ENTRY_SCALE}, {@code HALF_UP} (design-ledger.md §9.1, §9.2).
     */
    public static Money ofRateSeconds(BigDecimal rateSeconds, Currency currency) {
        Objects.requireNonNull(rateSeconds, "rateSeconds");
        return new Money(rateSeconds.divide(SECONDS_PER_HOUR, ENTRY_SCALE, RoundingMode.HALF_UP), currency);
    }

    /** Rounds once, {@code HALF_UP}, to the currency's minor unit (2 for USD and INR): a statement line. */
    public Money toMinorUnit() {
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalStateException("Currency has no minor unit: " + currency.getCurrencyCode());
        }
        return new Money(amount.setScale(digits, RoundingMode.HALF_UP), currency);
    }

    public int signum() {
        return amount.signum();
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /** The larger of this and zero in the same currency: a negative net bills 0 (PO-26). */
    public Money floorAtZero() {
        return isNegative() ? zero(currency) : this;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money other && currency.equals(other.currency) && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        BigDecimal canonical = amount.signum() == 0 ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return Objects.hash(canonical, currency);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
