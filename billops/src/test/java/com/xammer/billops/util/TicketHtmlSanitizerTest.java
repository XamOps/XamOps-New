package com.xammer.billops.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ticket sanitizer policy on both sides of the tradeoff it makes.
 *
 * <p>Too strict and support threads lose their formatting, which is the reason the field was
 * rich text in the first place and the reason someone would be tempted to remove the
 * sanitizer. Too loose and a customer can plant a payload in a reply that fires inside an
 * agent's authenticated session the moment they open the ticket. Both halves are asserted
 * here so a future policy tweak cannot quietly break either one.
 */
class TicketHtmlSanitizerTest {

    @Test
    void keepsInlineFormatting() {
        String html = "<p><b>bold</b> <i>italic</i> <u>underline</u></p>";
        assertEquals(html, TicketHtmlSanitizer.sanitize(html));
    }

    @Test
    void keepsBlocksAndLists() {
        String out = TicketHtmlSanitizer.sanitize(
                "<blockquote>quoted</blockquote><ol><li>one</li></ol><ul><li>two</li></ul><pre>code()</pre>");
        assertTrue(out.contains("<blockquote>quoted</blockquote>"));
        assertTrue(out.contains("<li>one</li>"));
        assertTrue(out.contains("<li>two</li>"));
        assertTrue(out.contains("<pre>code()</pre>"));
    }

    @Test
    void keepsQuillClassesUsedForIndentAndHighlighting() {
        String out = TicketHtmlSanitizer.sanitize("<pre class=\"ql-syntax\">x</pre>");
        assertTrue(out.contains("class=\"ql-syntax\""));
    }

    @Test
    void keepsPastedInlineImages() {
        String out = TicketHtmlSanitizer.sanitize(
                "<img src=\"data:image/png;base64,iVBORw0KGgo=\" alt=\"screenshot\">");
        // The sanitizer entity-encodes '=' as &#61;, which the browser decodes back
        // when the attribute is parsed, so the base64 payload still resolves.
        assertTrue(out.contains("data:image/png;base64,iVBORw0KGgo"));
        assertTrue(out.contains("alt=\"screenshot\""));
    }

    @Test
    void keepsHttpLinks() {
        String out = TicketHtmlSanitizer.sanitize("<a href=\"https://example.com\">docs</a>");
        assertTrue(out.contains("href=\"https://example.com\""));
        assertTrue(out.contains("docs"));
    }

    @Test
    void dropsScriptElements() {
        String out = TicketHtmlSanitizer.sanitize("<p>hi</p><script>alert(1)</script>");
        assertFalse(out.contains("script"));
        assertFalse(out.contains("alert"));
        assertTrue(out.contains("hi"));
    }

    @Test
    void dropsEventHandlerAttributes() {
        String out = TicketHtmlSanitizer.sanitize("<img src=\"x\" onerror=\"alert(1)\">");
        assertFalse(out.contains("onerror"));
        assertFalse(out.contains("alert"));
    }

    @Test
    void dropsJavascriptUrls() {
        String out = TicketHtmlSanitizer.sanitize("<a href=\"javascript:alert(1)\">click</a>");
        assertFalse(out.contains("javascript:"));
        assertTrue(out.contains("click"));
    }

    @Test
    void dropsIframesFormsAndInlineStyles() {
        assertFalse(TicketHtmlSanitizer.sanitize("<iframe src=\"https://evil.test\"></iframe>").contains("iframe"));
        assertFalse(TicketHtmlSanitizer.sanitize("<form action=\"/x\"><input name=\"y\"></form>").contains("form"));
        assertFalse(TicketHtmlSanitizer.sanitize("<p style=\"position:fixed\">x</p>").contains("style"));
    }

    @Test
    void blankInputBecomesEmptyString() {
        assertEquals("", TicketHtmlSanitizer.sanitize(null));
        assertEquals("", TicketHtmlSanitizer.sanitize(""));
        assertEquals("", TicketHtmlSanitizer.sanitize("   "));
    }
}
