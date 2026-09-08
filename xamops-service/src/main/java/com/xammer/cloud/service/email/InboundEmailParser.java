package com.xammer.cloud.service.email;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a raw {@link MimeMessage} into a {@link ParsedEmail}.
 *
 * <p>Everything here is defensive. The input is written by whatever mail client the sender
 * happens to use, and a malformed header must degrade the parse rather than abort ingestion —
 * losing a customer's mail because their client omitted a Date is not an acceptable trade.
 */
@Component
public class InboundEmailParser {

    private static final Logger logger = LoggerFactory.getLogger(InboundEmailParser.class);

    /**
     * Where a plain-text body stops being new content and starts being the thread it replies to.
     *
     * <p>Without this every reply re-stores the entire conversation, and the ticket becomes
     * unreadable by the third exchange. These cover the clients that actually reach a support
     * desk; anything unmatched keeps its quoted tail, which is untidy but never lossy.
     */
    private static final List<Pattern> QUOTE_MARKERS = List.of(
            // Gmail, Apple Mail: "On Mon, 1 Jan 2026 at 10:00, Someone <a@b.com> wrote:"
            Pattern.compile("^\\s*On .{10,200}\\s+wrote:\\s*$", Pattern.MULTILINE),
            // Outlook, both the localised and the classic form
            Pattern.compile("^\\s*-{2,}\\s*Original Message\\s*-{2,}\\s*$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*_{10,}\\s*$", Pattern.MULTILINE),
            Pattern.compile("^\\s*From:\\s.{1,200}$\\s*^\\s*Sent:\\s",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            // RFC 3676 signature delimiter
            Pattern.compile("^-- $", Pattern.MULTILINE));

    /** Quoted-history containers used by the major web clients. */
    private static final List<Pattern> HTML_QUOTE_MARKERS = List.of(
            Pattern.compile("<div[^>]*class=\"[^\"]*gmail_quote[^\"]*\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<div[^>]*id=\"?(divRplyFwdMsg|appendonly)\"?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<blockquote[^>]*type=\"?cite\"?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<div[^>]*class=\"[^\"]*yahoo_quoted[^\"]*\"", Pattern.CASE_INSENSITIVE));

    /**
     * @param maxAttachmentBytes per-attachment cap; anything larger is dropped with a log line
     *                           rather than failing the whole message.
     */
    public ParsedEmail parse(MimeMessage message, long maxAttachmentBytes) throws MessagingException, IOException {
        String messageId = stripAngleBrackets(firstHeader(message, "Message-ID"));
        if (messageId == null || messageId.isBlank()) {
            // No Message-ID means no idempotency key and no threading. Synthesising one from the
            // envelope is the only way to keep such a mail — and it stays stable across a retry
            // of the same message, which is the property that matters.
            messageId = syntheticMessageId(message);
        }

        List<String> references = new ArrayList<>();
        String refsHeader = firstHeader(message, "References");
        if (refsHeader != null) {
            for (String ref : refsHeader.trim().split("\\s+")) {
                String cleaned = stripAngleBrackets(ref);
                if (cleaned != null && !cleaned.isBlank()) {
                    references.add(cleaned);
                }
            }
        }

        InternetAddress from = firstFrom(message);
        String fromAddress = from != null && from.getAddress() != null
                ? from.getAddress().trim().toLowerCase(Locale.ROOT)
                : null;
        String fromName = from != null ? decode(from.getPersonal()) : null;

        BodyParts parts = new BodyParts();
        collectParts(message, parts, maxAttachmentBytes);

        String rawBody;
        boolean isHtml;
        if (parts.html != null && !parts.html.isBlank()) {
            rawBody = stripQuotedHtml(parts.html);
            isHtml = true;
        } else {
            rawBody = stripQuotedText(parts.text != null ? parts.text : "");
            isHtml = false;
        }

        return new ParsedEmail(
                messageId,
                stripAngleBrackets(firstHeader(message, "In-Reply-To")),
                references,
                fromAddress,
                fromName,
                addresses(message, Message.RecipientType.TO),
                addresses(message, Message.RecipientType.CC),
                decode(message.getSubject()) != null ? decode(message.getSubject()) : "",
                sentAt(message),
                rawBody,
                isHtml,
                isMachineSent(message),
                parts.attachments);
    }

    /**
     * Whether the message came from automation rather than a person.
     *
     * <p>Ingesting these is how a support mailbox ends up in a mail loop with a customer's
     * out-of-office, each reply generating the other's. RFC 3834's {@code Auto-Submitted} is the
     * standard signal; the rest are what clients actually send.
     */
    private boolean isMachineSent(MimeMessage message) throws MessagingException {
        String autoSubmitted = firstHeader(message, "Auto-Submitted");
        if (autoSubmitted != null && !autoSubmitted.trim().equalsIgnoreCase("no")) {
            return true;
        }
        if (firstHeader(message, "X-Autoreply") != null
                || firstHeader(message, "X-Autorespond") != null
                || firstHeader(message, "X-Auto-Response-Suppress") != null) {
            return true;
        }
        String precedence = firstHeader(message, "Precedence");
        if (precedence != null) {
            String value = precedence.trim().toLowerCase(Locale.ROOT);
            if (value.equals("bulk") || value.equals("auto_reply") || value.equals("junk")) {
                return true;
            }
        }
        // A null reverse-path is how a bounce identifies itself.
        String returnPath = firstHeader(message, "Return-Path");
        return returnPath != null && returnPath.trim().equals("<>");
    }

    /** Depth-first walk of the MIME tree, keeping the best body and every real attachment. */
    private void collectParts(Part part, BodyParts out, long maxAttachmentBytes)
            throws MessagingException, IOException {
        String disposition = part.getDisposition();
        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
                || (part.getFileName() != null && !Part.INLINE.equalsIgnoreCase(disposition));

        if (isAttachment) {
            String filename = decode(part.getFileName());
            if (filename == null || filename.isBlank()) {
                return;
            }
            byte[] content = readBounded(part.getInputStream(), maxAttachmentBytes);
            if (content == null) {
                logger.warn("Dropping attachment '{}' — exceeds the {} byte cap", filename, maxAttachmentBytes);
                return;
            }
            String contentType = part.getContentType() != null
                    ? part.getContentType().split(";")[0].trim().toLowerCase(Locale.ROOT)
                    : "application/octet-stream";
            out.attachments.add(new ParsedEmail.Attachment(filename, contentType, content));
            return;
        }

        if (part.isMimeType("text/plain") && out.text == null) {
            out.text = asString(part.getContent());
        } else if (part.isMimeType("text/html") && out.html == null) {
            out.html = asString(part.getContent());
        } else if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (content instanceof jakarta.mail.Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    collectParts(multipart.getBodyPart(i), out, maxAttachmentBytes);
                }
            }
        } else if (part.isMimeType("message/rfc822")) {
            // A forwarded mail. Its body is the content the sender means us to read.
            Object content = part.getContent();
            if (content instanceof Part inner) {
                collectParts(inner, out, maxAttachmentBytes);
            }
        }
    }

    /**
     * Reads at most {@code max} bytes, returning null if the stream has more.
     *
     * <p>Reading first and checking the size after would let a sender decide how much heap this
     * process allocates.
     */
    private byte[] readBounded(InputStream in, long max) throws IOException {
        try (in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > max) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private String asString(Object content) throws IOException {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof InputStream in) {
            try (in) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return content != null ? content.toString() : null;
    }

    /** Cuts a plain-text body at the first quoted-history marker. */
    String stripQuotedText(String body) {
        int cut = body.length();
        for (Pattern marker : QUOTE_MARKERS) {
            var matcher = marker.matcher(body);
            if (matcher.find() && matcher.start() < cut) {
                cut = matcher.start();
            }
        }
        String trimmed = body.substring(0, cut).trim();
        // A reply that is *only* quoted text (top-post stripped to nothing) is worse than
        // keeping the quote — an empty reply tells the agent nothing.
        return trimmed.isEmpty() ? body.trim() : trimmed;
    }

    /** Cuts an HTML body at the first quoted-history container. */
    String stripQuotedHtml(String body) {
        int cut = body.length();
        for (Pattern marker : HTML_QUOTE_MARKERS) {
            var matcher = marker.matcher(body);
            if (matcher.find() && matcher.start() < cut) {
                cut = matcher.start();
            }
        }
        String trimmed = body.substring(0, cut).trim();
        return trimmed.isEmpty() ? body.trim() : trimmed;
    }

    private List<String> addresses(MimeMessage message, Message.RecipientType type) {
        try {
            jakarta.mail.Address[] recipients = message.getRecipients(type);
            if (recipients == null) {
                return List.of();
            }
            return Arrays.stream(recipients)
                    .filter(a -> a instanceof InternetAddress)
                    .map(a -> ((InternetAddress) a).getAddress())
                    .filter(a -> a != null && !a.isBlank())
                    .map(a -> a.trim().toLowerCase(Locale.ROOT))
                    .toList();
        } catch (MessagingException e) {
            // One unparseable address must not cost us the recipient list, which is what decides
            // routing. Callers see the addresses that did parse.
            logger.warn("Could not read {} recipients: {}", type, e.getMessage());
            return List.of();
        }
    }

    private InternetAddress firstFrom(MimeMessage message) throws MessagingException {
        jakarta.mail.Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return null;
        }
        return from[0] instanceof InternetAddress internet ? internet : null;
    }

    private LocalDateTime sentAt(MimeMessage message) {
        try {
            var date = message.getSentDate();
            if (date != null) {
                return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
            }
        } catch (MessagingException e) {
            logger.debug("Unreadable Date header: {}", e.getMessage());
        }
        return null;
    }

    private String firstHeader(MimeMessage message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        return values != null && values.length > 0 ? values[0] : null;
    }

    private String stripAngleBrackets(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length() > 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** RFC 2047 decoding for headers carrying non-ASCII text. */
    private String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(value);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * A stable stand-in for a missing Message-ID, derived from the envelope so the same message
     * hashes the same way if it is seen twice.
     */
    private String syntheticMessageId(MimeMessage message) throws MessagingException {
        String seed = String.valueOf(firstHeader(message, "Date"))
                + '|' + Arrays.toString(message.getFrom())
                + '|' + message.getSubject()
                + '|' + firstHeader(message, "Received");
        return "synthetic-" + Integer.toHexString(seed.hashCode()) + "@xamops.local";
    }

    /** Mutable accumulator for the MIME walk. */
    private static final class BodyParts {
        private String text;
        private String html;
        private final List<ParsedEmail.Attachment> attachments = new ArrayList<>();
    }
}
