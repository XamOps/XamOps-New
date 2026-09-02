package com.xammer.cloud.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What may be attached to a ticket.
 *
 * <p>
 * The SVG and HTML cases are the reason this policy exists: attachments are served back to
 * other participants, so those file types are stored XSS that never passes through the
 * reply-body sanitiser. The content-type-lying case matters just as much, because the type
 * is client-supplied.
 */
class TicketAttachmentPolicyTest {

    private static MockMultipartFile file(String name, String contentType) {
        return new MockMultipartFile("files", name, contentType, "payload".getBytes());
    }

    // ── Accepted ──────────────────────────────────────────────────────────────

    @Test
    void acceptsScreenshotsAndLogs() {
        assertThatCode(() -> TicketAttachmentPolicy.validate(file("screenshot.png", "image/png")))
                .doesNotThrowAnyException();
        assertThatCode(() -> TicketAttachmentPolicy.validate(file("app.log", "text/plain")))
                .doesNotThrowAnyException();
        assertThatCode(() -> TicketAttachmentPolicy.validate(file("invoice.pdf", "application/pdf")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOctetStreamForLogLikeFiles() {
        // Browsers and CLIs commonly send this for .log/.yaml; the extension constrains it.
        assertThatCode(() -> TicketAttachmentPolicy.validate(
                file("values.yaml", "application/octet-stream"))).doesNotThrowAnyException();
    }

    // ── Rejected ──────────────────────────────────────────────────────────────

    @Test
    void rejectsSvg() {
        // An image to the user, a scriptable document to the browser.
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(file("logo.svg", "image/svg+xml")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not permitted");
    }

    @Test
    void rejectsHtml() {
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(file("poc.html", "text/html")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(file("poc.htm", "text/html")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHtmlDisguisedByContentType() {
        // Content-Type is attacker-controlled, so the extension check has to stand alone.
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(file("poc.html", "image/png")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDisallowedContentTypeOnAllowedExtension() {
        // Both must agree, so a permitted extension cannot smuggle a renderable type.
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(file("a.png", "text/html")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExecutablesAndArchives() {
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(file("x.sh", "text/plain")))
                .isInstanceOf(IllegalArgumentException.class);
        // Archives hide their contents from this check.
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(file("x.zip", "application/zip")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPathTraversalInFilename() {
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(file("../../etc/passwd.png", "image/png")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filename is not permitted");
    }

    @Test
    void rejectsMissingExtension() {
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(file("noextension", "image/png")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyAndNull() {
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TicketAttachmentPolicy.validate(
                new MockMultipartFile("files", "a.png", "image/png", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toleratesContentTypeParameters() {
        assertThatCode(() -> TicketAttachmentPolicy.validate(
                file("notes.txt", "text/plain; charset=utf-8"))).doesNotThrowAnyException();
    }
}
