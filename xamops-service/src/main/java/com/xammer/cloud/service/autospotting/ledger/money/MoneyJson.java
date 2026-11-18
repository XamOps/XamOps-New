package com.xammer.cloud.service.autospotting.ledger.money;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Currency;

/**
 * JSON form of {@link Money}: {@code {"amount":"12.3400000000","currency":"USD"}}. The amount is a
 * decimal string ({@link BigDecimal#toPlainString()}), never a JSON number, so no reader can turn it
 * into a binary float on the way (FR-LED-17). Reading accepts the same two fields; the amount must be
 * a string or an integer-valued or decimal token that Jackson hands over as text, and is parsed with
 * {@code new BigDecimal(String)}.
 */
public final class MoneyJson {

    private MoneyJson() {
    }

    /** Writes the amount as a string with the currency code. */
    public static final class Serializer extends StdSerializer<Money> {

        public Serializer() {
            super(Money.class);
        }

        @Override
        public void serialize(Money value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("amount", value.amount().toPlainString());
            gen.writeStringField("currency", value.currency().getCurrencyCode());
            gen.writeEndObject();
        }
    }

    /** Reads {@code {amount, currency}}; rejects a missing field or an unknown currency code. */
    public static final class Deserializer extends JsonDeserializer<Money> {

        @Override
        public Money deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() != JsonToken.START_OBJECT) {
                throw JsonMappingException.from(p, "Money must be an object with amount and currency");
            }
            String amount = null;
            String currency = null;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                if ("amount".equals(field)) {
                    amount = p.getText();
                } else if ("currency".equals(field)) {
                    currency = p.getText();
                } else {
                    p.skipChildren();
                }
            }
            if (amount == null || currency == null) {
                throw JsonMappingException.from(p, "Money needs both amount and currency");
            }
            try {
                return new Money(new BigDecimal(amount), Currency.getInstance(currency));
            } catch (IllegalArgumentException e) {
                throw JsonMappingException.from(p, "Money has an invalid amount or currency");
            }
        }
    }
}
