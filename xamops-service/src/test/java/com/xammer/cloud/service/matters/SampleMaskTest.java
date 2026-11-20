package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Build 9 design sections 2.1 and 2.2: the half rule, the secret rule and the secret-type decision. */
class SampleMaskTest {
    private static final int M = SampleMask.MASK;

    static Stream<Arguments> halfVectors() {
        return Stream.of(
                Arguments.of("", ""),
                Arguments.of("a", "•"),
                Arguments.of("ab", "••"),
                Arguments.of("abc", "•••"),
                Arguments.of("abcd", "ab••"),
                Arguments.of("abcde", "ab•••"),
                Arguments.of("abcdef", "abc•••"),
                Arguments.of("Jane Doe", "Ja•• D••"),
                Arguments.of("<<Jane Doe>>", "<<Ja•• D••>>"),
                Arguments.of("<<Sukhbir Singh>>", "<<Suk•••• Si•••>>"),
                Arguments.of("jane.doe@example.com", "ja••.d••@exa••••.c••"),
                Arguments.of("a@b.co", "•@•.c•"),
                Arguments.of("+1 555 0100", "•••••• 0100"),
                Arguments.of("4111 1111 1111 1111", "•••• •••• •••• 1111"),
                Arguments.of("4111-1111-1111-1111", "••••-••••-••••-1111"),
                Arguments.of("1234", "••34"),
                Arguments.of("12345", "•••45"),
                Arguments.of("192.168.1.10", "••••••8.1.10"),
                Arguments.of("AKIAIOSFODNN7EXAMPLE", "AKIAIOSFOD••••••••••"),
                Arguments.of("Zo\u00eb \u00c5ngstr\u00f6m", "Z•• \u00c5ngs••••"),
                Arguments.of("日本語テキスト", "日本語••••"),
                Arguments.of("\uD83D\uDC4D\uD83C\uDFFD\uD83D\uDC4D\uD83C\uDFFD", "\uD83D\uDC4D\uD83C\uDFFD••"),
                Arguments.of("J*** D**", "J••• D••"),
                Arguments.of("j*******@e******.com", "j•••••••@e••••••.com"),
                Arguments.of("+1 ***-***-0142", "+• •••-•••-••42"),
                Arguments.of("********4021", "••••••••4021"),
                Arguments.of("*****1234*", "•••••1234•"),
                Arguments.of("  r***@example.test ", "  r•••@ex•••••.t••• "),
                Arguments.of("<<ab>>", "<<••>>"),
                Arguments.of("<<>>", "<<>>"),
                Arguments.of("<<SYNTHETIC-SAMPLE-7F3A>>", "<<SYNT•••••-SAM•••-7•••>>"),
                Arguments.of("<<Second Sample>>", "<<Sec••• Sa••••>>"),
                Arguments.of("SYNTHMARKER-a", "SYNTH••••••-•"),
                Arguments.of("j***@e******.org", "j•••@e••••••.org"),
                Arguments.of("********", "••••••••"),
                Arguments.of("<<PLANTED-RAW-9Q4Z-SAMPLE>>", "<<PLA••••-R••-9Q••-SA••••>>"),
                Arguments.of("sample-0", "sam•••-•"),
                Arguments.of("sample-19", "sa••••-1•"),
                Arguments.of("pat@example.com", "p••@exa••••.c••"),
                Arguments.of("ab12cd34", "ab12••••"));
    }

    static Stream<Arguments> secretVectors() {
        return Stream.of(
                Arguments.of("", ""),
                Arguments.of("abc", "•••"),
                Arguments.of("abcd", "ab••"),
                Arguments.of("abcde", "ab•••"),
                Arguments.of("abcdefgh", "abcd••••"),
                Arguments.of("AKIAIOSFODNN7EXAMPLE", "AKIA••••••••••••••••"),
                Arguments.of("AKIA************DEMO", "AKIA••••••••••••••••"),
                Arguments.of("<<AKIA…DEMO1>>", "<<AKIA••••••>>"),
                Arguments.of("<<9f2c…e41a>>", "<<9f2c•••••>>"),
                Arguments.of("$2b$12$abcdefghij", "$2b$•••••••••••••"),
                Arguments.of("ghp_x1Y2z3W4v5U6", "ghp_••••••••••••"),
                Arguments.of("<<PLANTED-RAW-9Q4Z-SAMPLE>>", "<<PLAN•••••••••••••••••••>>"),
                Arguments.of("<<SYNTHETIC-SAMPLE-7F3A>>", "<<SYNT•••••••••••••••••>>"));
    }

    private static int[] cps(String s) {
        return s.codePoints().toArray();
    }

    private static long masks(int[] cps) {
        return Arrays.stream(cps).filter(cp -> cp == M).count();
    }

    /** The part the rule counts: the inside of a {@code <<…>>} frame, else the whole value. */
    private static int[] counted(String s) {
        int[] all = cps(s);
        int n = all.length;
        if (n >= 4 && all[0] == '<' && all[1] == '<' && all[n - 2] == '>' && all[n - 1] == '>') {
            return Arrays.copyOfRange(all, 2, n - 2);
        }
        return all;
    }

    private static void assertHalf(String out) {
        int[] part = counted(out);
        int n = part.length;
        if (n < 4) {
            assertThat(masks(part)).as("under 4 code points is all masked: %s", out).isEqualTo(n);
        } else {
            assertThat(masks(part)).as("at least half masked: %s", out).isGreaterThanOrEqualTo((n + 1) / 2);
        }
    }

    @ParameterizedTest(name = "mask({0}) = {1}")
    @MethodSource("halfVectors")
    @DisplayName("half rule: every vector exactly, same length, at least half masked, idempotent, no *")
    void halfRule(String input, String expected) {
        String out = SampleMask.mask(input);
        assertThat(out).isEqualTo(expected);
        assertThat(cps(out)).hasSameSizeAs(cps(input));
        assertHalf(out);
        assertThat(SampleMask.mask(out)).isEqualTo(out);
        assertThat(out).doesNotContain("*");
    }

    @ParameterizedTest(name = "maskSecret({0}) = {1}")
    @MethodSource("secretVectors")
    @DisplayName("secret rule: every vector exactly, at most 4 visible, at least half masked, idempotent")
    void secretRule(String input, String expected) {
        String out = SampleMask.maskSecret(input);
        assertThat(out).isEqualTo(expected);
        assertThat(cps(out)).hasSameSizeAs(cps(input));
        int[] part = counted(out);
        assertThat(part.length - masks(part)).as("at most 4 visible: %s", out).isLessThanOrEqualTo(4);
        assertHalf(out);
        assertThat(SampleMask.maskSecret(out)).isEqualTo(out);
        assertThat(SampleMask.mask(out)).as("the half-rule floor leaves a secret output unchanged").isEqualTo(out);
        assertThat(out).doesNotContain("*");
    }

    @Test
    @DisplayName("null stays null for both rules")
    void nullValue() {
        assertThat(SampleMask.mask(null)).isNull();
        assertThat(SampleMask.maskSecret(null)).isNull();
    }

    @Test
    @DisplayName("maskAll: null is empty, order kept, nulls dropped, result unmodifiable")
    void maskAll() {
        assertThat(SampleMask.maskAll(null)).isEmpty();
        List<String> in = new ArrayList<>(Arrays.asList("Jane Doe", null, "1234"));
        List<String> out = SampleMask.maskAll(in);
        assertThat(out).containsExactly("Ja•• D••", "••34");
        assertThatThrownBy(() -> out.add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(SampleMask.maskAll(out)).isEqualTo(out);
    }

    static Stream<Arguments> secretTypes() {
        return Stream.of(
                Arguments.of("ACCESS KEY AWS", List.of("SECRETS"), true),
                Arguments.of("SHA KEY", List.of(), true),
                Arguments.of("HASHED SECRET KEY", null, true),
                Arguments.of("DB PASSWORD", List.of(), true),
                Arguments.of("GITHUB TOKEN", List.of(), true),
                Arguments.of("NEW DETECTOR", List.of("secrets"), true),
                Arguments.of("X", List.of("IT & Security"), true),
                Arguments.of("PERSON NAME", List.of("PII"), false),
                Arguments.of("EMAIL ADDRESS", List.of("PII"), false),
                Arguments.of("MONKEY NAME", List.of(), false),
                Arguments.of("", List.of(), false),
                Arguments.of(null, null, false));
    }

    @ParameterizedTest(name = "isSecret({0}, {1}) = {2}")
    @MethodSource("secretTypes")
    @DisplayName("secret type by label, else by name word")
    void isSecret(String name, List<String> labels, boolean expected) {
        assertThat(SampleMask.isSecret(name, labels)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a label list holding null is read safely")
    void nullLabel() {
        assertThat(SampleMask.isSecret("PERSON NAME", Arrays.asList(null, "PII"))).isFalse();
        assertThat(SampleMask.isSecret("PERSON NAME", Arrays.asList(null, "SECRETS"))).isTrue();
    }
}
