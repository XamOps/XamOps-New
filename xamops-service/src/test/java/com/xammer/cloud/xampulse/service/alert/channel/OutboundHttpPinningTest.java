package com.xammer.cloud.xampulse.service.alert.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.service.alert.OutboundUrlGuard;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OutboundHttpPinningTest {

    private static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolverReturnsOnlyTheValidatedAddressesFromASingleLookup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // Hostile DNS: public on the first answer, loopback on the second.
        OutboundUrlGuard guard = new OutboundUrlGuard(host ->
                calls.incrementAndGet() == 1 ? List.of(ip("93.184.216.34")) : List.of(ip("127.0.0.1")));
        DnsResolver resolver = OutboundHttp.guardedResolver(guard);

        InetAddress[] pinned = resolver.resolve("hooks.example.com");

        assertEquals(1, calls.get());
        assertArrayEquals(new InetAddress[] {ip("93.184.216.34")}, pinned);
        // A second lookup (a rebind) is validated again and refused.
        assertThrows(UnknownHostException.class, () -> resolver.resolve("hooks.example.com"));
    }

    @Test
    void resolverRefusesWhenAnyAnswerIsPrivate() {
        OutboundUrlGuard guard = new OutboundUrlGuard(host -> List.of(ip("93.184.216.34"), ip("10.0.0.5")));
        assertThrows(UnknownHostException.class, () -> OutboundHttp.guardedResolver(guard).resolve("rebind.example.com"));
    }

    @Test
    void postToAHostThatResolvesToLoopbackNeverConnects() {
        AtomicInteger calls = new AtomicInteger();
        OutboundUrlGuard guard = new OutboundUrlGuard(host -> {
            calls.incrementAndGet();
            return List.of(ip("127.0.0.1"));
        });
        OutboundHttp http = new OutboundHttp(guard, new ObjectMapper());
        assertThrows(IOException.class, () -> http.postJson("https://evil.example.com/hook", java.util.Map.of("a", 1)));
        assertEquals(1, calls.get(), "exactly one resolution per request");
    }

    @Test
    void syntaxChecksStillApplyBeforeAnyLookup() {
        AtomicInteger calls = new AtomicInteger();
        OutboundUrlGuard guard = new OutboundUrlGuard(host -> {
            calls.incrementAndGet();
            return List.of(ip("93.184.216.34"));
        });
        OutboundHttp http = new OutboundHttp(guard, new ObjectMapper());
        assertThrows(RuntimeException.class, () -> http.postJson("http://hooks.example.com/x", java.util.Map.of()));
        assertEquals(0, calls.get());
    }
}
