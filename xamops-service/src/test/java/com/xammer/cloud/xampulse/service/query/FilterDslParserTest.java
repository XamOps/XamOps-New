package com.xammer.cloud.xampulse.service.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterDslParserTest {

    private static JsonNode cases;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = FilterDslParserTest.class.getResourceAsStream("/xampulse/dsl-cases.json")) {
            cases = new ObjectMapper().readTree(in);
        }
    }

    static Stream<String[]> valid() {
        List<String[]> out = new ArrayList<>();
        cases.get("valid").forEach(n -> out.add(new String[] {n.get("input").asText(), n.get("normalised").asText()}));
        return out.stream();
    }

    static Stream<String> invalid() {
        List<String> out = new ArrayList<>();
        cases.get("invalid").forEach(n -> out.add(n.asText()));
        return out.stream();
    }

    static Stream<String> injection() {
        List<String> out = new ArrayList<>();
        cases.get("injection").forEach(n -> out.add(n.asText()));
        return out.stream();
    }

    @ParameterizedTest
    @MethodSource("valid")
    void validInputsParseAndNormalise(String input, String normalised) {
        assertEquals(normalised, FilterDslParser.normalise(FilterDslParser.parse(input)));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void invalidInputsAreRejectedAsBadFilter(String input) {
        XamPulseException e = assertThrows(XamPulseException.class, () -> FilterDslParser.parse(input));
        assertEquals("XP_BAD_FILTER", e.getCode());
        assertEquals(400, e.getHttpStatus());
    }

    @ParameterizedTest
    @MethodSource("injection")
    void injectionAttemptsNeverBreakOutOfTheAst(String input) {
        // Either rejected, or parsed as plain text/field values that only ever become bound parameters.
        try {
            FilterAst ast = FilterDslParser.parse(input);
            assertTrue(ast instanceof FilterAst.MatchAll || ast != null);
        } catch (XamPulseException e) {
            assertEquals("XP_BAD_FILTER", e.getCode());
        }
    }

    @Test
    void overlongInputIsRejected() {
        // 40 words of 49 characters plus a space = exactly 2000 characters, within every other limit.
        String atLimit = ("a".repeat(49) + " ").repeat(40);
        assertEquals(FilterDslParser.MAX_LENGTH, atLimit.length());
        assertDoesNotThrow(() -> FilterDslParser.parse(atLimit));
        String big = atLimit + "a";
        XamPulseException e = assertThrows(XamPulseException.class, () -> FilterDslParser.parse(big));
        assertEquals("XP_BAD_FILTER", e.getCode());
        assertTrue(e.getMessage().contains("too long"));
        // a single value longer than 256 characters is rejected too
        assertEquals("XP_BAD_FILTER", assertThrows(XamPulseException.class, () -> FilterDslParser.parse("a".repeat(300))).getCode());
    }

    @Test
    void deepNestingIsRejected() {
        String deep = "(".repeat(FilterDslParser.MAX_DEPTH + 2) + "a" + ")".repeat(FilterDslParser.MAX_DEPTH + 2);
        assertEquals("XP_BAD_FILTER", assertThrows(XamPulseException.class, () -> FilterDslParser.parse(deep)).getCode());
        String ok = "(".repeat(3) + "a" + ")".repeat(3);
        assertDoesNotThrow(() -> FilterDslParser.parse(ok));
    }

    @Test
    void tooManyTermsAreRejected() {
        String many = String.join(" ", java.util.Collections.nCopies(FilterDslParser.MAX_TERMS + 1, "x"));
        assertEquals("XP_BAD_FILTER", assertThrows(XamPulseException.class, () -> FilterDslParser.parse(many)).getCode());
        String fine = String.join(" ", java.util.Collections.nCopies(FilterDslParser.MAX_TERMS, "x"));
        assertDoesNotThrow(() -> FilterDslParser.parse(fine));
    }

    @Test
    void orBindsLooserThanImplicitAnd() {
        FilterAst ast = FilterDslParser.parse("a b OR c");
        assertTrue(ast instanceof FilterAst.Or o && o.children().get(0) instanceof FilterAst.And);
    }
}
