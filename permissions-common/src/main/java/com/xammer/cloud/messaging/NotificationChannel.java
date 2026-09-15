package com.xammer.cloud.messaging;

/**
 * Delivery channels for XamOps notifications.
 *
 * <p>Introduced because channel selection was previously a {@code String.contains()} test
 * against the CSV in {@code support_message.auto_ping_channels} — which also matched any
 * channel whose name contained another's, and gave no compile-time list of what was
 * supported. {@link #fromCsv} keeps the stored format while making the parse exact.
 */
public enum NotificationChannel {

    EMAIL,
    SMS,
    WHATSAPP;

    /**
     * Exact-match parse of the stored comma-separated channel list.
     *
     * @param csv e.g. {@code "WHATSAPP,SMS,EMAIL"}; null or blank yields an empty set
     */
    public static java.util.Set<NotificationChannel> fromCsv(String csv) {
        java.util.Set<NotificationChannel> channels =
                java.util.EnumSet.noneOf(NotificationChannel.class);
        if (csv == null || csv.isBlank()) {
            return channels;
        }
        for (String token : csv.split(",")) {
            String name = token.trim().toUpperCase(java.util.Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            try {
                channels.add(NotificationChannel.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // Unknown channel in stored data — ignore rather than fail the whole dispatch.
            }
        }
        return channels;
    }
}
