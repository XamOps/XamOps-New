package com.xammer.billops.util;

import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/**
 * What may be attached to a support ticket.
 *
 * <p>
 * Attachments were accepted with no restriction on type or extension and are served back to
 * other participants. That makes the attachment endpoint a stored-XSS vector independent of
 * the reply body: uploading an {@code .html} or {@code .svg} file and sending the link to a
 * support agent runs script in the agent's session when they open it. SVG matters as much as
 * HTML here — it is an image to a user, and a scriptable document to a browser.
 *
 * <p>
 * The sanitiser on reply bodies does not help, because the payload never passes through it.
 *
 * <h2>Allowlist, and why extension as well as content type</h2>
 * {@code Content-Type} on an upload is supplied by the client and trivially set to
 * {@code image/png} for an HTML file. The extension is what a browser and an object store
 * tend to act on when the file is later served. Both are therefore checked, and both must
 * pass — the pair being consistent is what makes the type meaningful.
 *
 * <p>
 * Serving attachments with {@code Content-Disposition: attachment} and a non-renderable
 * content type is the complementary control; this class bounds what gets stored in the first
 * place.
 */
public final class TicketAttachmentPolicy {

    /**
     * Extensions accepted on ticket attachments: screenshots, logs, and the document formats
     * customers actually send with a support request.
     *
     * <p>
     * Deliberately excludes {@code svg} (scriptable), {@code html}/{@code htm},
     * {@code xhtml}, {@code xml}, and anything executable. Archives are excluded too: they
     * hide their contents from this check, so allowing them would make the allowlist
     * decorative.
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "pdf",
            "txt", "log", "csv", "json", "yaml", "yml",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx");

    /** Content types that may accompany the extensions above. */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
            "application/pdf",
            "text/plain", "text/csv", "application/json",
            "application/yaml", "text/yaml", "text/x-yaml",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            // Browsers and CLIs send this for .log/.yaml and similar; the extension check is
            // what constrains those.
            "application/octet-stream");

    /** 25 MB. Bounds storage cost and the obvious denial-of-service angle. */
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    private TicketAttachmentPolicy() {
    }

    /**
     * @throws IllegalArgumentException if the file may not be attached. The message is safe to
     *                                  return to the caller.
     */
    public static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Attachment is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Attachment exceeds the 25 MB limit");
        }

        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Attachment has no filename");
        }

        // Reject path separators outright rather than trying to normalise them: the filename
        // is stored and echoed back, and a traversal sequence has no legitimate use here.
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("Attachment filename is not permitted");
        }

        String extension = extensionOf(name);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Attachment type '" + (extension == null ? "none" : extension)
                            + "' is not permitted. Allowed: images, PDF, text/log/CSV/JSON/YAML "
                            + "and Office documents.");
        }

        String contentType = file.getContentType();
        if (contentType != null) {
            // Strip any parameters, e.g. "text/plain; charset=utf-8".
            String bare = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_CONTENT_TYPES.contains(bare)) {
                throw new IllegalArgumentException(
                        "Attachment content type '" + bare + "' is not permitted");
            }
        }
    }

    /** Lowercased extension without the dot, or null when there is none. */
    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
