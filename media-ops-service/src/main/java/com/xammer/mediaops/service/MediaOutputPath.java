package com.xammer.mediaops.service;

/**
 * Builds the key prefix a job's transcoded output is written under.
 *
 * When the source was picked from S3 we mirror its path so results land somewhere the
 * customer recognises — {@code DRSAKSHI/1.mp4} in the input bucket comes back as
 * {@code xamops-mediaops/DRSAKSHI/1.mp4/HLS-46/} in the output bucket, rather than an
 * opaque {@code jobs/46/} that says nothing about which video it holds.
 *
 * The trailing {@code <FORMAT>-<jobId>} segment is what keeps that safe: one source is
 * routinely transcoded more than once (an HLS + MP4 batch, or a retry), and without it
 * the second job would silently overwrite the first — leaving the earlier job showing
 * "Completed" in the UI while its playlist and segments were gone.
 *
 * Output is always a directory, never a single file: HLS writes a playlist plus hundreds
 * of segments, so every format gets a folder of its own for consistency.
 */
public final class MediaOutputPath {

    /** Root prefix under which all XamOps transcode output is written in a customer bucket. */
    public static final String OUTPUT_ROOT = "xamops-mediaops";

    /**
     * S3 keys are capped at 1024 bytes and the segment filenames we append can run to ~30
     * characters, so leave the mirrored portion comfortably short of the limit.
     */
    private static final int MAX_MIRRORED_LENGTH = 700;

    private MediaOutputPath() {}

    /** Fallback layout for sources with no S3 key — direct URLs and uploads. */
    public static String forJob(long jobId) {
        return OUTPUT_ROOT + "/jobs/" + jobId;
    }

    /**
     * Mirrored layout: {@code xamops-mediaops/<source path>/<FORMAT>-<jobId>}.
     * Falls back to {@link #forJob} when the key is missing or sanitises to nothing.
     *
     * @param sourceKey key the source was read from, e.g. {@code DRSAKSHI/1.mp4}
     * @param format    output format for this job, e.g. {@code HLS}
     * @param jobId     job id, disambiguating repeat transcodes of the same source
     */
    public static String forSource(String sourceKey, String format, long jobId) {
        String mirrored = sanitize(sourceKey);
        if (mirrored.isEmpty()) {
            return forJob(jobId);
        }
        String suffix = (format == null || format.isBlank() ? "OUTPUT" : format) + "-" + jobId;
        return OUTPUT_ROOT + "/" + mirrored + "/" + suffix;
    }

    /**
     * Reduces a caller-supplied key to something safe to use as a key prefix.
     *
     * The key arrives from the browser, so it is untrusted. "." and ".." carry no meaning
     * to S3, but they would produce keys no one can address through a console or CLI that
     * normalises paths, so they are dropped along with empty segments and control
     * characters.
     */
    static String sanitize(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) return "";

        StringBuilder out = new StringBuilder();
        for (String rawSegment : sourceKey.split("/")) {
            String segment = stripControlChars(rawSegment).trim();
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) continue;
            if (out.length() > 0) out.append('/');
            out.append(segment);
        }

        String mirrored = out.toString();
        if (mirrored.length() > MAX_MIRRORED_LENGTH) {
            // Keep the tail: the filename identifies the video far better than the
            // leading folders do.
            mirrored = mirrored.substring(mirrored.length() - MAX_MIRRORED_LENGTH);
            int firstSlash = mirrored.indexOf('/');
            if (firstSlash >= 0) mirrored = mirrored.substring(firstSlash + 1);
        }
        return mirrored;
    }

    private static String stripControlChars(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        return sb.toString();
    }
}
