package com.xammer.cloud.xampulse.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.dto.agent.HeartbeatRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentHeartbeatHealthTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void noHealthFieldsStoresNothing() {
        assertNull(AgentSessionService.healthJson(null));
        assertNull(AgentSessionService.healthJson(new HeartbeatRequest("1", "1", 1L, null, null, null)));
    }

    @Test
    void storesSourcesErrorsAndQueueBoundedAndClean() throws Exception {
        List<HeartbeatRequest.SourceStatus> sources = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            sources.add(new HeartbeatRequest.SourceStatus("src" + i, "OK", null));
        }
        sources.set(0, new HeartbeatRequest.SourceStatus("nginx\n", "ERROR", "x".repeat(500)));
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            errors.add("e\u0000" + i);
        }
        String json = AgentSessionService.healthJson(new HeartbeatRequest("1", "1", 1L,
                new HeartbeatRequest.Queue(10L, 2L), sources, errors));
        JsonNode n = M.readTree(json);
        assertEquals(AgentSessionService.MAX_SOURCES, n.get("sourcesStatus").size());
        assertEquals("nginx", n.get("sourcesStatus").get(0).get("source").asText());
        assertEquals(200, n.get("sourcesStatus").get(0).get("error").asText().length());
        assertEquals(AgentSessionService.MAX_ERRORS, n.get("errors").size());
        assertEquals("e0", n.get("errors").get(0).asText());
        assertEquals(10, n.get("queue").get("bytes").asLong());
        assertEquals(2, n.get("queue").get("dropped").asLong());
    }
}
