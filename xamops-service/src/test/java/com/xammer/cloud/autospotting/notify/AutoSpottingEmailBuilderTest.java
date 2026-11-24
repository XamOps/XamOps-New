package com.xammer.cloud.autospotting.notify;

import com.xammer.cloud.domain.autospotting.AutoSpottingNotification;
import com.xammer.cloud.service.autospotting.notify.AutoSpottingEmailBuilder;
import com.xammer.cloud.service.autospotting.notify.AutoSpottingEmailBuilder.Email;
import com.xammer.cloud.service.autospotting.notify.NotificationEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-OBS-03 for the AutoSpotting notice email (design-ux-ops.md section 7.4): for every event type, no colour
 * outside the black, white and grey set, no emoji code point and no non-ASCII character at all, in the subject,
 * the HTML and the plain text. Also: the deep link, HTML escaping, USD formatting, and that the builder reads
 * only the payload (a raw provider message in it is never printed).
 */
class AutoSpottingEmailBuilderTest {

    private static final UUID GROUP = UUID.fromString("7f3c0000-0000-4000-8000-000000000001");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");
    private static final Pattern FUNCTIONAL_COLOR = Pattern.compile("(?i)\\b(rgb|rgba|hsl|hsla)\\s*\\(");
    private static final Pattern NAMED_COLOR = Pattern.compile("(?i)(color|background|fill|stroke|border[a-z-]*)\\s*[:=]\\s*['\"]?"
            + "(red|green|blue|orange|yellow|amber|crimson|purple|pink|teal|cyan|magenta|lime|navy|maroon|gold)\\b");

    private final AutoSpottingEmailBuilder builder = new AutoSpottingEmailBuilder("https://live.example.test/");

    private static String payloadFor(NotificationEvent e) {
        return switch (e) {
            case APPLY_FAILED -> "{\"groupKey\":\"us-east-1/web-asg\",\"provider\":\"AWS\",\"accountName\":\"Prod AWS\","
                    + "\"occurredAt\":\"2026-11-02T10:58:00Z\",\"outcome\":\"RESTORED\",\"errorCode\":\"CAPACITY\","
                    + "\"requestId\":\"7f3c\",\"providerMessage\":\"AccessDenied: arn:aws:iam::1:role/x secret\"}";
            case REVERT_FAILED -> "{\"groupKey\":\"us-east-1/web-asg\",\"provider\":\"AWS\",\"accountName\":\"Prod AWS\","
                    + "\"outcome\":\"NEEDS_REVIEW\",\"errorCode\":\"RESTORE_FAILED\"}";
            case DEGRADED_LONG -> "{\"groupKey\":\"us-east-1/web-asg\",\"provider\":\"AWS\",\"accountName\":\"Prod AWS\","
                    + "\"minutes\":30}";
            case ADMIN_ENABLED -> "{\"groupKey\":\"proj/us-central1/mig-1\",\"provider\":\"GCP\","
                    + "\"accountName\":\"Prod GCP\",\"confirmedBy\":\"admin@xamops.test\","
                    + "\"reason\":\"Customer asked on ticket 42\"}";
            case REVERT_STARTED, REVERT_COMPLETED -> "{\"groupKey\":\"us-east-1/web-asg\",\"provider\":\"AWS\","
                    + "\"accountName\":\"Prod AWS\",\"confirmedBy\":\"admin@xamops.test\",\"reason\":\"incident 12\"}";
            case STATEMENT_READY -> "{\"provider\":\"AWS\",\"period\":\"2026-10\",\"currency\":\"USD\","
                    + "\"totalBillable\":\"1234.565\",\"accountName\":\"Prod AWS\"}";
            case DISPUTE_UPDATED -> "{\"period\":\"2026-10\",\"lineNo\":3,\"status\":\"RESOLVED_ACCEPTED\","
                    + "\"accountName\":\"Prod AWS\"}";
            default -> "{\"groupKey\":\"us-east-1/web-asg\",\"provider\":\"AWS\",\"accountName\":\"Prod AWS\","
                    + "\"occurredAt\":\"2026-11-02T10:58:00Z\",\"operation\":\"ENABLE\"}";
        };
    }

    private static AutoSpottingNotification row(NotificationEvent e, String payload) {
        boolean finance = e.finance();
        return new AutoSpottingNotification(1L, e.name(), "CUSTOMER", e.mandatory(), 7L, 42L, finance ? null : GROUP,
                null, "k:" + e.name(), payload, Instant.parse("2026-11-02T10:58:00Z"), null, 0, null, "[]");
    }

    @ParameterizedTest
    @EnumSource(NotificationEvent.class)
    void noColourOutsideTheGreySetAndNoEmojiOrNonAsciiInAnyTemplate(NotificationEvent event) {
        Email email = builder.build(row(event, payloadFor(event)));
        for (String part : new String[] {email.subject(), email.html(), email.text()}) {
            assertThat(part).as(event + " is ASCII only").matches("[\\x09\\x0A\\x0D\\x20-\\x7E]*");
            part.codePoints().forEach(cp -> assertThat(isEmojiRange(cp)).as("emoji code point %x in %s", cp, event)
                    .isFalse());
        }
        Matcher m = HEX.matcher(email.html());
        while (m.find()) {
            assertThat(AutoSpottingEmailBuilder.ALLOWED_COLORS).as(event + " colour " + m.group())
                    .contains(m.group().toLowerCase(Locale.ROOT));
        }
        assertThat(FUNCTIONAL_COLOR.matcher(email.html()).find()).as(event + " has rgb()/hsl()").isFalse();
        assertThat(NAMED_COLOR.matcher(email.html()).find()).as(event + " has a named colour").isFalse();
        assertThat(email.html()).contains("<svg").doesNotContain("<img").doesNotContain("<script");
    }

    @ParameterizedTest
    @EnumSource(NotificationEvent.class)
    void everyEventBuildsASubjectAndABody(NotificationEvent event) {
        Email email = builder.build(row(event, payloadFor(event)));
        assertThat(email.subject()).startsWith("[XamOps] AutoSpotting: " + event.title());
        assertThat(email.html()).contains("AutoSpotting").contains("Account").contains("When");
        assertThat(email.text()).contains("AutoSpotting");
        assertThat(builder.title(row(event, payloadFor(event)))).isNotBlank();
        assertThat(builder.summary(row(event, payloadFor(event)))).isNotBlank();
    }

    @Test
    void allowedColoursAreOnlyBlackWhiteAndGrey() {
        for (String color : AutoSpottingEmailBuilder.ALLOWED_COLORS) {
            int r = Integer.parseInt(color.substring(1, 3), 16);
            int g = Integer.parseInt(color.substring(3, 5), 16);
            int b = Integer.parseInt(color.substring(5, 7), 16);
            assertThat(r).as(color).isEqualTo(g).isEqualTo(b);
        }
    }

    @Test
    void applyFailedFollowsTheDesignedLayout_andLinksToTheGroup() {
        Email email = builder.build(row(NotificationEvent.APPLY_FAILED, payloadFor(NotificationEvent.APPLY_FAILED)));
        assertThat(email.subject()).isEqualTo("[XamOps] AutoSpotting: Apply failed - web-asg (Prod AWS)");
        assertThat(email.html()).contains("Apply failed and was rolled back")
                .contains("web-asg  (Auto Scaling Group, us-east-1)")
                .contains("2026-11-02 10:58 UTC")
                .contains("Code: CAPACITY")
                .contains("Original configuration restored.")
                .contains("Request id")
                .contains("href='https://live.example.test/autospotting/groups/" + GROUP + "'")
                .contains("View group")
                .contains("You receive failure and safety notices");
        assertThat(email.text()).contains("https://live.example.test/autospotting/groups/" + GROUP);
    }

    @Test
    void theBuilderReadsOnlyTheRedactedPayload_aRawProviderMessageIsNeverPrinted() {
        Email email = builder.build(row(NotificationEvent.APPLY_FAILED, payloadFor(NotificationEvent.APPLY_FAILED)));
        assertThat(email.html() + email.text() + email.subject()).doesNotContain("AccessDenied")
                .doesNotContain("arn:aws").doesNotContain("secret");
    }

    @Test
    void valuesAreEscaped_andControlCharactersCannotInjectHeaders() {
        String payload = "{\"groupKey\":\"us-east-1/<script>alert(1)</script>\\r\\nBcc: x@y\","
                + "\"provider\":\"AWS\",\"accountName\":\"A&B \\\"Co\\\"\"}";
        Email email = builder.build(row(NotificationEvent.PLAN_APPLIED, payload));
        assertThat(email.html()).doesNotContain("<script>").contains("&lt;script&gt;").contains("A&amp;B");
        assertThat(email.subject()).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void statementAmountIsUsdWithTwoDecimalsRoundedHalfUp() {
        Email email = builder.build(row(NotificationEvent.STATEMENT_READY, payloadFor(NotificationEvent.STATEMENT_READY)));
        assertThat(email.html()).contains("USD 1,234.57").contains("2026-10");
        assertThat(email.html()).contains("href='https://live.example.test/autospotting'"); // no group to link to
    }

    @Test
    void adminEnabledNamesTheAdminAndTheReason() {
        Email email = builder.build(row(NotificationEvent.ADMIN_ENABLED, payloadFor(NotificationEvent.ADMIN_ENABLED)));
        assertThat(email.html()).contains("admin@xamops.test").contains("Customer asked on ticket 42")
                .contains("mig-1  (Managed Instance Group, us-central1)");
    }

    @Test
    void mandatoryNoticesCarryTheFailureFooter_optionalOnesTheOptOutLine() {
        Email failure = builder.build(row(NotificationEvent.APPLY_FAILED, payloadFor(NotificationEvent.APPLY_FAILED)));
        Email applied = builder.build(row(NotificationEvent.PLAN_APPLIED, payloadFor(NotificationEvent.PLAN_APPLIED)));
        assertThat(failure.html()).doesNotContain("turn off AutoSpotting email");
        assertThat(applied.html()).contains("turn off AutoSpotting email").contains("Failure notices are always sent.");
        assertThat(Set.of(NotificationEvent.APPLY_FAILED, NotificationEvent.REVERT_FAILED)).allMatch(NotificationEvent::mandatory);
        assertThat(NotificationEvent.PLAN_APPLIED.mandatory()).isFalse();
        assertThat(NotificationEvent.REVERT_STARTED.mandatory()).isFalse();
        assertThat(NotificationEvent.REVERT_COMPLETED.mandatory()).isFalse();
    }

    private static boolean isEmojiRange(int cp) {
        return cp >= 0x1F000 && cp <= 0x1FAFF
                || cp >= 0x2600 && cp <= 0x27BF
                || cp >= 0x2300 && cp <= 0x23FF
                || cp >= 0x2B00 && cp <= 0x2BFF
                || cp >= 0x2190 && cp <= 0x21FF
                || cp >= 0x2900 && cp <= 0x297F
                || cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 || cp == 0x00A9 || cp == 0x00AE
                || cp >= 0xE0020 && cp <= 0xE007F;
    }
}
