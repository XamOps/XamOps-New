package com.xammer.billops.util;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Sanitizes user-authored ticket HTML before it is persisted.
 *
 * <p>Ticket replies and internal notes are rich text typed by customers and
 * agents, stored verbatim, and later rendered for every other participant in the
 * thread. The React client sanitizes on render as well; doing it here too means
 * a stored payload never survives in the database, so a future consumer of this
 * data (an email digest, an export, a new UI) cannot reintroduce the hole.
 *
 * <p>The allowlist covers everything the Quill editor can produce — inline
 * formatting, blockquotes, code blocks, lists, links and inline images — plus
 * what agents commonly paste. Scripts, event handlers, inline styles and every
 * other form of active content are dropped.
 *
 * <p>This is expressed as a single {@link HtmlPolicyBuilder} rather than a
 * composition of the prepackaged {@code Sanitizers} constants: combining
 * factories with {@code and()} silently drops global attribute policies such as
 * {@code class}, and omits {@code pre}, which is how the editor encodes code
 * blocks.
 */
public final class TicketHtmlSanitizer {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            // http, https and mailto; `data` additionally covers pasted screenshots,
            // which the editor embeds as base64 image URIs.
            .allowStandardUrlProtocols()
            .allowUrlProtocols("data")
            .allowElements(
                    // Block structure
                    "p", "div", "br", "hr", "blockquote", "pre",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "ul", "ol", "li",
                    // Inline formatting
                    "span", "b", "strong", "i", "em", "u", "s", "strike",
                    "sub", "sup", "small", "code",
                    // Links, media and tables
                    "a", "img",
                    "table", "thead", "tbody", "tfoot", "tr", "th", "td",
                    "figure", "figcaption")
            .allowAttributes("href").onElements("a")
            .allowAttributes("src", "alt", "title", "width", "height").onElements("img")
            .allowAttributes("colspan", "rowspan").onElements("td", "th")
            .allowAttributes("start", "type").onElements("ol", "ul", "li")
            // Quill encodes indent level and code highlighting as ql-* classes.
            .allowAttributes("class", "dir").globally()
            .requireRelNofollowOnLinks()
            .toFactory();

    private TicketHtmlSanitizer() {
    }

    /**
     * @param html raw HTML as submitted by the client
     * @return sanitized HTML, or an empty string when {@code html} is null/blank
     */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return POLICY.sanitize(html);
    }
}
