package com.xammer.cloud.xampulse.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpAgentFleet;
import com.xammer.cloud.xampulse.dto.XpAgentHealth;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import com.xammer.cloud.xampulse.service.setup.InstanceCandidateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only fleet view over {@code xp_agent} for one authorised account. Health comes from the sanitised, bounded JSON
 * the heartbeat stored; null or malformed JSON yields empty lists, never an exception. Status uses the same rule as the
 * setup instance list ({@link InstanceCandidateService#agentInfo}), so both screens agree.
 */
@Service("xpAgentFleetService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class AgentFleetService {

    static final int MAX_AGENTS = 500;
    static final int MAX_SOURCES = 32;
    static final int MAX_ERRORS = 10;

    private final XpAgentRepository agents;
    private final XamPulseAccessGuard guard;
    private final ObjectMapper mapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentFleetService(XpAgentRepository agents, XamPulseAccessGuard guard, ObjectMapper objectMapper) {
        this(agents, guard, objectMapper, Clock.systemUTC());
    }

    AgentFleetService(XpAgentRepository agents, XamPulseAccessGuard guard, ObjectMapper objectMapper, Clock clock) {
        this.agents = agents;
        this.guard = guard;
        this.mapper = objectMapper;
        this.clock = clock;
    }

    public XpAgentFleet fleet(ClientUserDetails user, Long accountId) {
        guard.require(user, accountId); // foreign or missing account: the usual 404
        Instant now = clock.instant();
        List<XpAgentHealth> out = new ArrayList<>();
        int active = 0;
        int stale = 0;
        int pending = 0;
        int revoked = 0;
        List<XpAgent> rows = agents.findByCloudAccountId(accountId).stream()
                .sorted(Comparator.comparing(XpAgent::getInstanceId, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_AGENTS).toList();
        for (XpAgent a : rows) {
            String status = InstanceCandidateService.agentInfo(a, now).status();
            if ("NONE".equals(status)) {
                status = "PENDING"; // paused by an admin: not reporting, not revoked
            }
            switch (status) {
                case "ACTIVE" -> active++;
                case "STALE" -> stale++;
                case "REVOKED" -> revoked++;
                default -> pending++;
            }
            out.add(toHealth(a, status));
        }
        return new XpAgentFleet(new XpAgentFleet.Counts(out.size(), active, stale, pending, revoked), out);
    }

    private XpAgentHealth toHealth(XpAgent a, String status) {
        List<XpAgentHealth.Source> sources = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Long queueBytes = null;
        Long dropped = null;
        if (a.getHealthJson() != null && !a.getHealthJson().isBlank()) {
            try {
                JsonNode root = mapper.readTree(a.getHealthJson());
                JsonNode src = root.path("sourcesStatus");
                if (src.isArray()) {
                    for (JsonNode s : src) {
                        if (sources.size() >= MAX_SOURCES) {
                            break;
                        }
                        String name = s.path("source").asText("");
                        if (!name.isEmpty()) {
                            String err = s.hasNonNull("error") ? s.get("error").asText() : null;
                            sources.add(new XpAgentHealth.Source(name, s.path("state").asText(""), err));
                        }
                    }
                }
                JsonNode errs = root.path("errors");
                if (errs.isArray()) {
                    for (JsonNode e : errs) {
                        if (errors.size() >= MAX_ERRORS) {
                            break;
                        }
                        if (e.isTextual()) {
                            errors.add(e.asText());
                        }
                    }
                }
                JsonNode q = root.path("queue");
                if (q.has("bytes") && q.get("bytes").canConvertToLong()) {
                    queueBytes = q.get("bytes").asLong();
                }
                if (q.has("dropped") && q.get("dropped").canConvertToLong()) {
                    dropped = q.get("dropped").asLong();
                }
            } catch (Exception e) {
                sources.clear();
                errors.clear();
                queueBytes = null;
                dropped = null;
            }
        }
        return new XpAgentHealth(a.getInstanceId(), null, status, a.getAgentVersion(), a.getLastSeenAt(),
                queueBytes, dropped, sources, errors);
    }
}
