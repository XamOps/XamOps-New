package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogRowIdCodecTest {

    @Test
    void roundTrips() {
        String id = LogRowIdCodec.encode(12, 1767225600123L, "i-0abc", "9876543210");
        LogRowIdCodec.RowRef r = LogRowIdCodec.decode(id);
        assertEquals(12, r.tenantId());
        assertEquals(1767225600123L, r.tsMs());
        assertEquals("i-0abc", r.instanceId());
        assertEquals("9876543210", r.bodyHash());
    }

    @Test
    void malformedAndTamperedIdsAreTheSameNotFound() {
        String[] bad = {null, "", "!!!", Base64.getUrlEncoder().encodeToString("1|2|3".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().encodeToString("x|2|i-1|3".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().encodeToString("1|2|i-1'; DROP|3".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().encodeToString("1|2|i-1|abc".getBytes(StandardCharsets.UTF_8)),
                "A".repeat(400)};
        for (String s : bad) {
            XamPulseException e = assertThrows(XamPulseException.class, () -> LogRowIdCodec.decode(s));
            assertEquals("XP_NOT_FOUND", e.getCode());
            assertEquals(404, e.getHttpStatus());
        }
    }

    @Test
    void pipesInInstanceIdAreStrippedSoTheIdCannotBeForged() {
        LogRowIdCodec.RowRef r = LogRowIdCodec.decode(LogRowIdCodec.encode(1, 2, "i-1|99|x", "5"));
        assertEquals("i-199x", r.instanceId());
        assertEquals(1, r.tenantId());
    }
}
