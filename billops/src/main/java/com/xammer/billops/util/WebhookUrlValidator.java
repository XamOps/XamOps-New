package com.xammer.billops.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Validates outbound messaging webhook URLs before the server fetches them.
 *
 * <p>
 * Slack, Teams and Google Chat messages are delivered by POSTing to a URL stored per user
 * connection. That URL arrived from a request body and was used unchecked — the Slack path
 * had no validation at all and the Teams path only checked that the string began with
 * {@code https://}, which a URL like {@code https://169.254.169.254/latest/meta-data/} also
 * satisfies. That makes the messaging feature a server-side request forgery primitive:
 * anyone who can save a connection gets the backend to issue POSTs to arbitrary internal
 * addresses, reaching cloud instance metadata and any service reachable from the pod.
 *
 * <h2>Allowlist rather than denylist</h2>
 * These are not arbitrary user URLs — each provider publishes webhooks on a small set of
 * fixed domains. Naming them is both stricter and simpler than trying to enumerate every
 * internal range an attacker might target, and it is not defeated by DNS rebinding or by
 * a redirect to a private address, which a denylist checked once at save time would be.
 *
 * <p>
 * The private-address check is kept as a second layer for defence in depth, so a future
 * addition to the host list cannot accidentally open an internal path.
 */
public final class WebhookUrlValidator {

    /**
     * Hosts that may receive a webhook POST.
     *
     * <p>
     * Matched as either an exact host or a dot-suffix, so {@code webhook.office.com} covers
     * {@code contoso.webhook.office.com} but never {@code webhook.office.com.attacker.net}.
     * {@code botframework.com} is present because the Teams path reuses this column to store
     * a Bot Framework {@code serviceUrl}; {@code logic.azure.com} covers Teams Workflows,
     * which replaced Office 365 connectors.
     */
    private static final List<String> ALLOWED_HOSTS = List.of(
            "hooks.slack.com",
            "slack.com",
            "webhook.office.com",
            "logic.azure.com",
            // Bot Framework serviceUrl, which the Teams path stores in this same column.
            // Values look like https://smba.trafficmanager.net/<region>/.
            //
            // Broader than the others: trafficmanager.net is shared Azure DNS, so this permits
            // any Azure Traffic Manager profile rather than only Microsoft's. Accepted because
            // the alternative is breaking the Teams two-way integration, and the remaining
            // exposure is a POST of a chat message to an https host that is not internal —
            // materially smaller than the arbitrary-URL fetch this replaces. Narrow it to the
            // specific regional hostnames once the set in use is known.
            "trafficmanager.net",
            "chat.googleapis.com");

    private WebhookUrlValidator() {
    }

    /**
     * Full validation: URL shape, approved host, and a DNS check that the host is not
     * internal.
     *
     * @throws IllegalArgumentException if the URL is not a webhook endpoint we are willing to
     *                                  POST to. Fails closed on anything unparseable.
     */
    public static void validate(String url) {
        String host = validateFormat(url);
        assertNotInternal(host);
    }

    /**
     * Everything checkable without touching the network: scheme, authority, and host
     * allowlist. Separated from the DNS check so the rules can be exercised without
     * resolution, and so a DNS outage cannot be confused with a rejected URL.
     *
     * @return the lowercased host, for the caller to run further checks against.
     * @throws IllegalArgumentException if the URL is malformed or the host is not approved.
     */
    public static String validateFormat(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Webhook URL is required");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Webhook URL is not a valid URL");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Webhook URL must use https");
        }
        if (uri.getUserInfo() != null) {
            // https://hooks.slack.com@attacker.test/ — the real host is the attacker's.
            throw new IllegalArgumentException("Webhook URL must not contain credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL has no host");
        }
        host = host.toLowerCase(Locale.ROOT);

        if (!isAllowedHost(host)) {
            throw new IllegalArgumentException(
                    "Webhook URL host is not an approved messaging provider endpoint");
        }
        return host;
    }

    /** Format-only form for call sites that want a boolean and no DNS lookup. */
    public static boolean isValidFormat(String url) {
        try {
            validateFormat(url);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Convenience form for call sites that want a boolean rather than an exception. */
    public static boolean isValid(String url) {
        try {
            validate(url);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isAllowedHost(String host) {
        for (String allowed : ALLOWED_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Second layer: even an allowlisted name must not resolve somewhere internal. Guards
     * against a future host-list edit, and against split-horizon DNS pointing a provider
     * name at an internal address.
     */
    private static void assertNotInternal(String host) {
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()) {
                    throw new IllegalArgumentException(
                            "Webhook URL resolves to a private or internal address");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Webhook URL host could not be resolved");
        }
    }
}
