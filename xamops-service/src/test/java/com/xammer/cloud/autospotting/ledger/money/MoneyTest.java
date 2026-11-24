package com.xammer.cloud.autospotting.ledger.money;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.ledger.money.CurrencyMismatchException;
import com.xammer.cloud.service.autospotting.ledger.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code Money} (design-ledger.md §9.1, §9.2, AC-LED-08): decimal only, currency-safe, rounding explicit. */
class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency INR = Currency.getInstance("INR");

    private static Money usd(String amount) {
        return Money.of(amount, "USD");
    }

    @Test
    void arithmeticIsExact_noBinaryFloatingPointError() {
        assertThat(usd("0.1").plus(usd("0.2"))).isEqualTo(usd("0.3"));
        assertThat(usd("0.3").minus(usd("0.1"))).isEqualTo(usd("0.2"));
        // Exact multiplication: the scale grows, nothing is rounded.
        Money product = usd("0.0345678901").times(new BigDecimal("3600"));
        assertThat(product.amount().toPlainString()).isEqualTo("124.4444043600");
        assertThat(product.amount().scale()).isEqualTo(10);
        assertThat(usd("1.10").negate()).isEqualTo(usd("-1.1"));
    }

    @Test
    void mixedCurrencies_throw_andNothingIsCoerced() {
        Money rupees = Money.of("10", "INR");
        assertThatThrownBy(() -> usd("1").plus(rupees)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> usd("1").minus(rupees)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> usd("1").compareTo(rupees)).isInstanceOf(CurrencyMismatchException.class);
        // Equal amounts in different currencies are not equal.
        assertThat(usd("10")).isNotEqualTo(rupees);
        assertThatThrownBy(() -> new Money(null, USD)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofRateSeconds_dividesOnceBy3600_toScale10_halfUp() {
        // 1 / 3600 = 0.000277777...: 10 dp rounds the 11th digit (7) up.
        assertThat(Money.ofRateSeconds(BigDecimal.ONE, USD).amount().toPlainString()).isEqualTo("0.0002777778");
        // A tie at the 11th decimal rounds away from zero: 0.5e-10 * 3600 = 1.8e-7 ... use a crafted sum.
        BigDecimal tie = new BigDecimal("0.00000018").multiply(BigDecimal.ONE); // 1.8e-7 / 3600 = 5e-11
        assertThat(Money.ofRateSeconds(tie, USD).amount().toPlainString()).isEqualTo("0.0000000001");
        assertThat(Money.ofRateSeconds(tie.negate(), USD).amount().toPlainString()).isEqualTo("-0.0000000001");
        // A whole hour of a 12-dp rate: 0.034567890123 * 3600 seconds / 3600.
        BigDecimal rate = new BigDecimal("0.034567890123");
        assertThat(Money.ofRateSeconds(rate.multiply(new BigDecimal("3600")), USD).amount().toPlainString())
                .isEqualTo("0.0345678901");
        assertThat(Money.ofRateSeconds(BigDecimal.ZERO, USD).amount().scale()).isEqualTo(Money.ENTRY_SCALE);
    }

    @Test
    void toMinorUnit_roundsOnce_halfUp_toTheCurrencyMinorUnit() {
        assertThat(usd("2.675").toMinorUnit().amount().toPlainString()).isEqualTo("2.68");
        assertThat(usd("0.005").toMinorUnit().amount().toPlainString()).isEqualTo("0.01");
        assertThat(usd("0.0049999999").toMinorUnit().amount().toPlainString()).isEqualTo("0.00");
        // HALF_UP is away from zero for a negative tie: the raw net stays visible, the line is rounded once.
        assertThat(usd("-0.005").toMinorUnit().amount().toPlainString()).isEqualTo("-0.01");
        assertThat(usd("1234.5").toMinorUnit().amount().toPlainString()).isEqualTo("1234.50");
        assertThat(new Money(new BigDecimal("1.005"), INR).toMinorUnit().amount().toPlainString()).isEqualTo("1.01");
        assertThat(Money.of("5", "JPY").toMinorUnit().amount().toPlainString()).isEqualTo("5");
    }

    @Test
    void negativeNetBillsZero_andKeepsTheRawValue() {
        Money raw = usd("-3.1234567890");
        assertThat(raw.isNegative()).isTrue();
        assertThat(raw.floorAtZero()).isEqualTo(Money.zero(USD));
        assertThat(raw.amount().toPlainString()).isEqualTo("-3.1234567890");
        assertThat(usd("2.5").floorAtZero()).isEqualTo(usd("2.5"));
        assertThat(Money.zero(USD).signum()).isZero();
    }

    @Test
    void equalityComparesTheValue_notTheScale() {
        assertThat(usd("1.50")).isEqualTo(usd("1.5")).hasSameHashCodeAs(usd("1.5000000000"));
        assertThat(usd("0.00")).isEqualTo(usd("0")).hasSameHashCodeAs(usd("0E-10"));
        assertThat(usd("1.5").compareTo(usd("1.50"))).isZero();
        assertThat(usd("1.5").compareTo(usd("2"))).isNegative();
    }

    @Test
    void json_writesTheAmountAsAString_andRoundTripsExactly() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Money money = usd("12.3400000000");
        String json = mapper.writeValueAsString(money);
        assertThat(json).isEqualTo("{\"amount\":\"12.3400000000\",\"currency\":\"USD\"}");
        Money back = mapper.readValue(json, Money.class);
        assertThat(back).isEqualTo(money);
        assertThat(back.amount().toPlainString()).isEqualTo("12.3400000000");
        // A 25-digit amount survives: it would not in a double.
        Money big = usd("12345678901234.1234567890");
        assertThat(mapper.readValue(mapper.writeValueAsString(big), Money.class).amount())
                .isEqualByComparingTo(big.amount());
        // A tiny exponent form never reaches the wire.
        assertThat(mapper.writeValueAsString(new Money(new BigDecimal("1E-10"), USD))).contains("\"0.0000000001\"");
    }

    @Test
    void json_rejectsMissingFieldsAndBadValues() {
        ObjectMapper mapper = new ObjectMapper();
        assertThatThrownBy(() -> mapper.readValue("{\"amount\":\"1\"}", Money.class)).isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"currency\":\"USD\"}", Money.class)).isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"amount\":\"abc\",\"currency\":\"USD\"}", Money.class))
                .isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"amount\":\"1\",\"currency\":\"XXQ\"}", Money.class))
                .isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(() -> mapper.readValue("\"1 USD\"", Money.class)).isInstanceOf(JsonMappingException.class);
    }
}
