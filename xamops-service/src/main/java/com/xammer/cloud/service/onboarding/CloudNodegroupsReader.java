package com.xammer.cloud.service.onboarding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Consumer of the agent's {@code cloud-nodegroups} row (design §8.1, F-9 option (a)). Returns the
 * parsed row only when it is newer than {@code maxAge}; a stale row or a parse error yields empty
 * (parse errors are logged WARN). Unknown JSON fields are ignored.
 */
@Service
public class CloudNodegroupsReader {

    private static final Logger logger = LoggerFactory.getLogger(CloudNodegroupsReader.class);
    private static final String DATA_TYPE = "cloud-nodegroups";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final AgentDataCacheRepository agentDataCacheRepository;

    public CloudNodegroupsReader(AgentDataCacheRepository agentDataCacheRepository) {
        this.agentDataCacheRepository = agentDataCacheRepository;
    }

    public record Nodegroup(String name, String status, String capacityType, Integer min, Integer desired,
                            Integer max, List<String> asgNames, String amiType, List<String> instanceTypes) {
    }

    public record Result(String collectedAt, List<Nodegroup> nodegroups) {
    }

    /** Read the fresh cloud-nodegroups row for a cluster, or empty if absent/stale/malformed. */
    public Optional<Result> read(KubernetesClusterConfig config, Duration maxAge) {
        Optional<AgentDataCache> rowOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(config.getId(), DATA_TYPE);
        if (rowOpt.isEmpty()) {
            return Optional.empty();
        }
        AgentDataCache row = rowOpt.get();
        if (row.getCollectedAt() == null) {
            return Optional.empty();
        }
        Instant collected = row.getCollectedAt().atOffset(ZoneOffset.UTC).toInstant();
        if (collected.isBefore(Instant.now().minus(maxAge))) {
            return Optional.empty(); // stale
        }
        try {
            JsonNode root = mapper.readTree(row.getDataJson());
            List<Nodegroup> nodegroups = new ArrayList<>();
            JsonNode arr = root.get("nodegroups");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    nodegroups.add(new Nodegroup(
                            text(n, "name"), text(n, "status"), text(n, "capacityType"),
                            intOrNull(n, "min"), intOrNull(n, "desired"), intOrNull(n, "max"),
                            stringList(n, "asgNames"), text(n, "amiType"), stringList(n, "instanceTypes")));
                }
            }
            String collectedAt = root.hasNonNull("collectedAt") ? root.get("collectedAt").asText() : collected.toString();
            return Optional.of(new Result(collectedAt, nodegroups));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.warn("cloud-nodegroups parse failed for cluster config {}: {}",
                    config.getId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static List<String> stringList(JsonNode n, String f) {
        List<String> out = new ArrayList<>();
        JsonNode v = n.get(f);
        if (v != null && v.isArray()) {
            v.forEach(e -> out.add(e.asText()));
        }
        return out;
    }
}
