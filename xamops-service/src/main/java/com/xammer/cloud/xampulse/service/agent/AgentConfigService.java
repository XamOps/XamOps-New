package com.xammer.cloud.xampulse.service.agent;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.domain.XpLogSource;
import com.xammer.cloud.xampulse.dto.XpLogSourceView;
import com.xammer.cloud.xampulse.dto.agent.AgentConfig;
import com.xammer.cloud.xampulse.repository.XpLogSourceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Renders the desired collection setup for one agent from the account's log sources, the agent's collection level and
 * the redaction defaults. Pure data: the agent turns it into collector YAML. Custom redaction rules are added to the
 * defaults, never replace them.
 */
@Service("xpAgentConfigService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class AgentConfigService {

    static final long MAX_BYTES_PER_SEC = 1_048_576L;
    static final long QUEUE_MAX_BYTES = 256L * 1024 * 1024;
    private static final Set<String> LEVELS = Set.of("T0", "T1", "T2");

    private final XpLogSourceRepository sources;
    private final XamPulseProperties props;

    public AgentConfigService(XpLogSourceRepository sources, XamPulseProperties props) {
        this.sources = sources;
        this.props = props;
    }

    public AgentConfig render(XpAgent agent) {
        List<XpLogSource> rows = sources.findByCloudAccountId(agent.getCloudAccountId());
        List<AgentConfig.Source> out = new ArrayList<>();
        List<AgentConfig.RedactionRule> redaction = new ArrayList<>(RedactionDefaults.RULES);
        if (rows.isEmpty()) {
            out.add(new AgentConfig.Source("SYSTEM", List.of(), null, null));
        }
        for (XpLogSource r : rows) {
            XpLogSourceView v = com.xammer.cloud.xampulse.service.setup.LogSourceService.toView(r);
            if (!r.isEnabled()) {
                continue;
            }
            out.add(new AgentConfig.Source(v.kind(), v.paths(), v.parser(), null));
            for (XpLogSourceView.RedactionRule rr : v.redaction()) {
                redaction.add(new AgentConfig.RedactionRule(rr.rule(), rr.pattern(), "[REDACTED]"));
            }
        }
        String level = agent.getCollectionLevel() != null && LEVELS.contains(agent.getCollectionLevel()) ? agent.getCollectionLevel() : "T1";
        return new AgentConfig(Math.max(1, agent.getConfigVersion()), level, List.copyOf(out), List.copyOf(redaction),
                new AgentConfig.Limits(MAX_BYTES_PER_SEC, QUEUE_MAX_BYTES), gatewayUrl());
    }

    /**
     * nginx exposes the OTLP gateway under /xampulse-ingest/ on the public host. {@code xampulse.gateway.public-url}
     * overrides it; when blank the API host the agents already talk to ({@code xampulse.setup.api-base-url}) is used,
     * because nginx-prod.conf serves both on one host. An empty result makes the agent refuse the config and restart,
     * so the fallback matters.
     */
    String gatewayUrl() {
        String base = props.getGateway().getPublicUrl();
        if (base == null || base.isBlank()) {
            base = props.getSetup().getApiBaseUrl();
        }
        if (base == null || base.isBlank()) {
            return "";
        }
        String trimmed = base.replaceAll("/+$", "");
        return trimmed.endsWith("/xampulse-ingest") ? trimmed : trimmed + "/xampulse-ingest";
    }

    public static String etag(AgentConfig c) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((c.configVersion() + "|" + c.level() + "|" + c.sources() + "|"
                    + c.redaction() + "|" + c.gatewayUrl()).getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(d, 0, 8) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
