package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Function;

/**
 * SSRF guard for customer-supplied notification URLs: https only, no credentials in the URL, and every address the
 * host resolves to must be publicly routable (no loopback, link-local, site-local, ULA, CGNAT, multicast, metadata).
 * Redirects are disabled by the HTTP client that uses this guard.
 *
 * <p>DNS rebinding: the check and the connection share one resolution. {@link #resolveAllowed(String)} resolves the
 * host once, rejects it if any address is blocked, and returns exactly the validated addresses. The outbound HTTP
 * client uses it as its DNS resolver, so the socket connects to an address that was just validated while TLS
 * name verification still uses the hostname.
 */
@Component("xpOutboundUrlGuard")
public class OutboundUrlGuard {

    private final Function<String, List<InetAddress>> resolver;

    public OutboundUrlGuard() {
        this(host -> {
            try {
                return List.of(InetAddress.getAllByName(host));
            } catch (UnknownHostException e) {
                return List.of();
            }
        });
    }

    /** For tests: inject a fake resolver (DNS rebinding cases). */
    public OutboundUrlGuard(Function<String, List<InetAddress>> resolver) {
        this.resolver = resolver;
    }

    public URI validate(String url) {
        URI uri = validateSyntax(url);
        resolveAllowed(uri.getHost());
        return uri;
    }

    /** Scheme, credentials, host and length checks only; no DNS lookup. */
    public URI validateSyntax(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw XamPulseException.invalid("The URL is not valid.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw XamPulseException.invalid("Only https URLs are allowed.");
        }
        if (uri.getUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw XamPulseException.invalid("The URL is not valid.");
        }
        if (url.length() > 2048) {
            throw XamPulseException.invalid("The URL is too long.");
        }
        return uri;
    }

    /**
     * Resolves the host once and returns the addresses a connection may use. Throws if the host does not resolve
     * or if any returned address is private or internal.
     */
    public List<InetAddress> resolveAllowed(String host) {
        List<InetAddress> addresses = resolver.apply(host);
        if (addresses == null || addresses.isEmpty()) {
            throw XamPulseException.invalid("The URL's host could not be resolved.");
        }
        for (InetAddress a : addresses) {
            if (isBlocked(a)) {
                throw XamPulseException.invalid("That URL points to a private or internal address.");
            }
        }
        return List.copyOf(addresses);
    }

    static boolean isBlocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return b0 == 0                                  // 0.0.0.0/8
                    || (b0 == 100 && b1 >= 64 && b1 <= 127) // 100.64.0.0/10 CGNAT
                    || (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24
                    || (b0 == 198 && (b1 == 18 || b1 == 19)) // 198.18.0.0/15
                    || b0 >= 240;                            // reserved / broadcast
        }
        int first = b[0] & 0xff;
        return (first & 0xfe) == 0xfc; // fc00::/7 unique local (includes fd00:ec2::254)
    }
}
