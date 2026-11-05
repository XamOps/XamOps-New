package com.xammer.cloud.xampulse.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpAgentFleet;
import com.xammer.cloud.xampulse.dto.XpAgentHealth;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentFleetServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private final XpAgentRepository agents = mock(XpAgentRepository.class);
    private final XamPulseAccessGuard guard = mock(XamPulseAccessGuard.class);
    private final AgentFleetService svc = new AgentFleetService(agents, guard, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final ClientUserDetails user = mock(ClientUserDetails.class);

    private XpAgent agent(String id, String state, Instant lastSeen, String health) {
        XpAgent a = new XpAgent();
        a.setCloudAccountId(7L);
        a.setInstanceId(id);
        a.setState(state);
        a.setLastSeenAt(lastSeen);
        a.setAgentVersion("1.2.3");
        a.setHealthJson(health);
        return a;
    }

    @Test
    void mapsStatusesAndCounts() {
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of(
                agent("i-1", "ACTIVE", NOW.minusSeconds(30), null),
                agent("i-2", "ACTIVE", NOW.minusSeconds(3600), null),
                agent("i-3", "ACTIVE", null, null),
                agent("i-4", "REVOKED", NOW.minusSeconds(10), null)));
        XpAgentFleet fleet = svc.fleet(user, 7L);
        assertEquals(new XpAgentFleet.Counts(4, 1, 1, 1, 1), fleet.counts());
        assertEquals(List.of("ACTIVE", "STALE", "PENDING", "REVOKED"), fleet.agents().stream().map(XpAgentHealth::status).toList());
    }

    @Test
    void staleBoundaryIsExactlyFiveMinutes() {
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of(
                agent("i-1", "ACTIVE", NOW.minusSeconds(300), null),
                agent("i-2", "ACTIVE", NOW.minusSeconds(301), null)));
        List<XpAgentHealth> out = svc.fleet(user, 7L).agents();
        assertEquals("ACTIVE", out.get(0).status());
        assertEquals("STALE", out.get(1).status());
    }

    @Test
    void parsesHealthJsonAndBoundsTheLists() {
        StringBuilder errs = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            errs.append(i == 0 ? "" : ",").append("\"e").append(i).append('"');
        }
        String health = "{\"sourcesStatus\":[{\"source\":\"nginx\",\"state\":\"OK\"},{\"source\":\"app\",\"state\":\"ERROR\",\"error\":\"denied\"}],"
                + "\"errors\":[" + errs + "],\"queue\":{\"bytes\":2048,\"dropped\":3}}";
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of(agent("i-1", "ACTIVE", NOW, health)));
        XpAgentHealth h = svc.fleet(user, 7L).agents().get(0);
        assertEquals(2, h.sources().size());
        assertEquals("denied", h.sources().get(1).error());
        assertEquals(AgentFleetService.MAX_ERRORS, h.errors().size());
        assertEquals(2048L, h.queueBytes());
        assertEquals(3L, h.droppedTotal());
        assertEquals("1.2.3", h.version());
    }

    @Test
    void nullOrMalformedHealthJsonGivesEmptyListsNotAnException() {
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of(
                agent("i-1", "ACTIVE", NOW, null),
                agent("i-2", "ACTIVE", NOW, "{not json"),
                agent("i-3", "ACTIVE", NOW, "{\"sourcesStatus\":\"oops\",\"errors\":5,\"queue\":[]}")));
        for (XpAgentHealth h : svc.fleet(user, 7L).agents()) {
            assertTrue(h.sources().isEmpty());
            assertTrue(h.errors().isEmpty());
            assertNull(h.queueBytes());
        }
    }

    @Test
    void aForeignAccountIsTheUsual404AndTheRepositoryIsNeverRead() {
        when(guard.require(any(), eq(9L))).thenThrow(XamPulseException.notFound());
        XamPulseException e = assertThrows(XamPulseException.class, () -> svc.fleet(user, 9L));
        assertEquals(404, e.getHttpStatus());
        verifyNoInteractions(agents);
    }
}
