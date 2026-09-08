package com.xammer.cloud.service.email;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class InboundEmailParserTest {

    private final InboundEmailParser parser = new InboundEmailParser();

    private MimeMessage message(String raw) throws Exception {
        Session session = Session.getInstance(new Properties());
        return new MimeMessage(session, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsSenderRecipientsAndBody() throws Exception {
        ParsedEmail mail = parser.parse(message("""
                Message-ID: <abc123@customer.com>
                From: Priya Nair <priya@customer.com>
                To: colleague@customer.com
                Cc: techtickets@xamops.com
                Subject: EC2 instance keeps restarting
                Content-Type: text/plain; charset=UTF-8

                Our production box has restarted four times today.
                """), 1024 * 1024);

        assertThat(mail.messageId()).isEqualTo("abc123@customer.com");
        assertThat(mail.fromAddress()).isEqualTo("priya@customer.com");
        assertThat(mail.fromName()).isEqualTo("Priya Nair");
        assertThat(mail.to()).containsExactly("colleague@customer.com");
        assertThat(mail.cc()).containsExactly("techtickets@xamops.com");
        assertThat(mail.subject()).isEqualTo("EC2 instance keeps restarting");
        assertThat(mail.body()).contains("restarted four times today");
        assertThat(mail.machineSent()).isFalse();
    }

    @Test
    void lowercasesAddressesSoRoutingAndMatchingAreCaseInsensitive() throws Exception {
        ParsedEmail mail = parser.parse(message("""
                Message-ID: <case@customer.com>
                From: Priya <Priya@Customer.COM>
                To: TechTickets@XamOps.com
                Subject: Mixed case

                body
                """), 1024 * 1024);

        assertThat(mail.fromAddress()).isEqualTo("priya@customer.com");
        assertThat(mail.to()).containsExactly("techtickets@xamops.com");
    }

    @Test
    void collectsAncestorsClosestFirstSoThreadingPrefersTheNearestMatch() throws Exception {
        ParsedEmail mail = parser.parse(message("""
                Message-ID: <newest@customer.com>
                In-Reply-To: <second@customer.com>
                References: <first@customer.com> <second@customer.com>
                From: priya@customer.com
                To: techtickets@xamops.com
                Subject: Re: EC2 instance keeps restarting

                Still happening.
                """), 1024 * 1024);

        assertThat(mail.inReplyTo()).isEqualTo("second@customer.com");
        assertThat(mail.references()).containsExactly("first@customer.com", "second@customer.com");
        // In-Reply-To first, then References newest → oldest, with no duplicates.
        assertThat(mail.ancestorMessageIds()).containsExactly("second@customer.com", "first@customer.com");
    }

    @Test
    void treatsAutoSubmittedAsMachineSent() throws Exception {
        ParsedEmail mail = parser.parse(message("""
                Message-ID: <ooo@customer.com>
                From: priya@customer.com
                To: techtickets@xamops.com
                Subject: Out of office
                Auto-Submitted: auto-replied

                I am away until Monday.
                """), 1024 * 1024);

        assertThat(mail.machineSent()).isTrue();
    }

    @Test
    void treatsANullReturnPathAsMachineSent() throws Exception {
        ParsedEmail mail = parser.parse(message("""
                Message-ID: <bounce@mailer>
                Return-Path: <>
                From: mailer-daemon@customer.com
                To: techtickets@xamops.com
                Subject: Undeliverable

                Delivery failed.
                """), 1024 * 1024);

        assertThat(mail.machineSent()).isTrue();
    }

    @Test
    void autoSubmittedNoIsAnOrdinaryHumanMessage() throws Exception {
        ParsedEmail mail = parser.parse(message("""
                Message-ID: <human@customer.com>
                From: priya@customer.com
                To: techtickets@xamops.com
                Subject: A real question
                Auto-Submitted: no

                Please help.
                """), 1024 * 1024);

        assertThat(mail.machineSent()).isFalse();
    }

    @Test
    void synthesisesAStableIdWhenTheClientOmittedMessageId() throws Exception {
        String raw = """
                From: priya@customer.com
                To: techtickets@xamops.com
                Subject: No message id here

                body
                """;

        String first = parser.parse(message(raw), 1024).messageId();
        String second = parser.parse(message(raw), 1024).messageId();

        // Stability is the point: the same mail seen twice must dedupe rather than double-post.
        assertThat(first).isNotBlank().isEqualTo(second);
    }

    @Test
    void prefersHtmlOverPlainTextInAMultipartAlternative() throws Exception {
        ParsedEmail mail = parser.parse(message("""
                Message-ID: <multipart@customer.com>
                From: priya@customer.com
                To: techtickets@xamops.com
                Subject: Formatted
                Content-Type: multipart/alternative; boundary="BOUND"

                --BOUND
                Content-Type: text/plain; charset=UTF-8

                plain version
                --BOUND
                Content-Type: text/html; charset=UTF-8

                <p>html version</p>
                --BOUND--
                """), 1024 * 1024);

        assertThat(mail.bodyIsHtml()).isTrue();
        assertThat(mail.body()).contains("html version");
    }

    @Test
    void extractsAttachments() throws Exception {
        ParsedEmail mail = parser.parse(message("""
                Message-ID: <withfile@customer.com>
                From: priya@customer.com
                To: techtickets@xamops.com
                Subject: Log attached
                Content-Type: multipart/mixed; boundary="BOUND"

                --BOUND
                Content-Type: text/plain; charset=UTF-8

                See attached.
                --BOUND
                Content-Type: text/plain; charset=UTF-8
                Content-Disposition: attachment; filename="error.log"

                stack trace here
                --BOUND--
                """), 1024 * 1024);

        assertThat(mail.attachments()).hasSize(1);
        assertThat(mail.attachments().get(0).filename()).isEqualTo("error.log");
        assertThat(new String(mail.attachments().get(0).content(), StandardCharsets.UTF_8))
                .contains("stack trace here");
        assertThat(mail.body()).contains("See attached.");
    }

    @Test
    void dropsAnAttachmentLargerThanTheCapWithoutLosingTheMessage() throws Exception {
        ParsedEmail mail = parser.parse(message("""
                Message-ID: <toobig@customer.com>
                From: priya@customer.com
                To: techtickets@xamops.com
                Subject: Huge file
                Content-Type: multipart/mixed; boundary="BOUND"

                --BOUND
                Content-Type: text/plain; charset=UTF-8

                Body survives.
                --BOUND
                Content-Type: text/plain; charset=UTF-8
                Content-Disposition: attachment; filename="big.log"

                this content is longer than the cap
                --BOUND--
                """), 8);

        assertThat(mail.attachments()).isEmpty();
        assertThat(mail.body()).contains("Body survives.");
    }

    @Test
    void stripsGmailStyleQuotedHistory() {
        String body = """
                Yes, still broken.

                On Mon, 1 Sep 2026 at 10:00, Support <techtickets@xamops.com> wrote:
                > Have you tried restarting?
                > Thanks
                """;

        assertThat(parser.stripQuotedText(body)).isEqualTo("Yes, still broken.");
    }

    @Test
    void stripsOutlookOriginalMessageBlock() {
        String body = """
                Confirming this is resolved.

                -----Original Message-----
                From: Support
                Sent: Monday
                """;

        assertThat(parser.stripQuotedText(body)).isEqualTo("Confirming this is resolved.");
    }

    @Test
    void keepsTheQuoteWhenStrippingWouldLeaveNothing() {
        // A pure bottom-post has no new content above the marker. An empty reply tells the agent
        // nothing, so the quote is worth more than the tidiness.
        String body = """
                On Mon, 1 Sep 2026 at 10:00, Support <techtickets@xamops.com> wrote:
                > Have you tried restarting?
                """;

        assertThat(parser.stripQuotedText(body)).contains("Have you tried restarting?");
    }

    @Test
    void stripsGmailQuoteContainerFromHtml() {
        String body = "<p>Still broken.</p>"
                + "<div class=\"gmail_quote\"><blockquote>old thread</blockquote></div>";

        assertThat(parser.stripQuotedHtml(body)).isEqualTo("<p>Still broken.</p>");
    }

    @Test
    void leavesAnUnquotedBodyAlone() {
        String body = "A plain message with no history at all.";
        assertThat(parser.stripQuotedText(body)).isEqualTo(body);
        assertThat(parser.stripQuotedHtml(body)).isEqualTo(body);
    }
}
