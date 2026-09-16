package com.xammer.cloud.messaging;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards SMS body rendering.
 *
 * <p>{@code POST /v1/sms/countrycode} takes both {@code message} and {@code template_id} and the
 * provider rejects the send when the rendered text does not match the approved DLT template. A
 * substitution bug therefore fails every SMS, and the bare {@code {#var#}} form is easy to get
 * wrong because it looks like a named placeholder but behaves positionally.
 */
class MessageTemplateTest {

    private static List<String> vars(String... values) {
        return List.of(values);
    }

    @Test
    void fillsBareDltPlaceholdersByPosition() {
        // The real DLT form: every placeholder spelled identically, filled in order. Reading the
        // name would resolve one key called "var" for all three and strip every value.
        String pattern = "Dear {#var#}, Scale securely with Xammer {#var#} cloud. Connect: {#var#}";

        String out = MessageTemplate.render(pattern, vars("Ayushi", "YOTTA", "xamops.com"));

        assertThat(out).isEqualTo("Dear Ayushi, Scale securely with Xammer YOTTA cloud. Connect: xamops.com");
    }

    @Test
    void fillsNumberedPlaceholdersOneBased() {
        assertThat(MessageTemplate.render("{#var2#} then {#var1#}", vars("first", "second")))
                .isEqualTo("second then first");
    }

    @Test
    void fillsDoubleBracePlaceholders() {
        assertThat(MessageTemplate.render("Hello {{var1}} and {{var2}}", vars("a", "b")))
                .isEqualTo("Hello a and b");
        assertThat(MessageTemplate.render("{{2}}-{{1}}", vars("a", "b"))).isEqualTo("b-a");
    }

    @Test
    void rendersTheRealAlertBodyWithinOneSmsSegment() {
        MessagingProperties props = new MessagingProperties();
        String out = MessageTemplate.render(props.getSms().getAlertBody(),
                vars("Production AWS", "4", "2"));

        assertThat(out).isEqualTo("XamOps alert for Production AWS: 4 resource(s) need attention, "
                + "2 critical. Log in to XamOps to review. - Xammer");
        assertThat(out).doesNotContain("{#");
        // Over 160 chars silently doubles the per-message cost.
        assertThat(out.length()).isLessThanOrEqualTo(160);
    }

    @Test
    void rendersTheRealReportBodyInTheOrderTheBuilderEmits() {
        MessagingProperties props = new MessagingProperties();
        // Argument order is FinOpsReportTextBuilder's contract: frequency, account, range, spend.
        String out = MessageTemplate.render(props.getSms().getReportBody(),
                vars("Daily", "Production AWS", "2026-09-14", "$12,480"));

        // Asserted as an exact string, not a contains(): positional placeholders mean a reordering
        // renders "total spend 2026-09-14" instead of failing loudly.
        assertThat(out).isEqualTo("Daily XamOps FinOps report for Production AWS (2026-09-14): "
                + "total spend $12,480. Log in for the full breakdown. - Xammer");
        assertThat(out.length()).isLessThanOrEqualTo(160);
    }

    @Test
    void rendersTheRealSupportBodyWithinOneSmsSegment() {
        MessagingProperties props = new MessagingProperties();
        String out = MessageTemplate.render(props.getSms().getSupportBody(),
                vars("ayushi", "Production AWS"));

        assertThat(out).doesNotContain("{#");
        assertThat(out.length()).isLessThanOrEqualTo(160);
    }

    @Test
    void dropsPlaceholdersWithNoValueRatherThanLeavingThemLiteral() {
        // A literal "{#var#}" arriving on a handset is worse than a terse sentence, and it would
        // also fail the provider's template match.
        assertThat(MessageTemplate.render("A {#var#} B {#var#} C", vars("one")))
                .isEqualTo("A one B  C");
    }

    @Test
    void toleratesMissingPatternAndVariables() {
        assertThat(MessageTemplate.render(null, vars("x"))).isEmpty();
        assertThat(MessageTemplate.render("   ", vars("x"))).isEmpty();
        assertThat(MessageTemplate.render("no placeholders", null)).isEqualTo("no placeholders");
    }
}
