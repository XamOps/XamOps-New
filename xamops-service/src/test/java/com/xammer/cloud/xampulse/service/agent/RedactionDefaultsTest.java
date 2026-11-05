package com.xammer.cloud.xampulse.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.domain.XpLogSource;
import com.xammer.cloud.xampulse.dto.agent.AgentConfig;
import com.xammer.cloud.xampulse.repository.XpLogSourceRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The default redaction rules, run against the same positive and negative samples the Go agent tests use
 * (xamops-agent/internal/xampulse/testdata/redaction-cases.json). If either side changes a pattern without the
 * other, one of the two suites fails.
 */
class RedactionDefaultsTest {

    private static final Path FIXTURE = Path.of("..", "xamops-agent", "internal", "xampulse", "testdata", "redaction-cases.json");

    @Test
    void theJavaDefaultsAreExactlyTheRulesInTheSharedFixture() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(FIXTURE));
        assertEquals(cases.size(), RedactionDefaults.RULES.size());
        for (int i = 0; i < cases.size(); i++) {
            AgentConfig.RedactionRule r = RedactionDefaults.RULES.get(i);
            assertEquals(cases.get(i).get("rule").asText(), r.rule());
            assertEquals(cases.get(i).get("pattern").asText(), r.pattern(), r.rule());
            assertEquals(cases.get(i).get("replacement").asText(), r.replacement(), r.rule());
        }
    }

    @Test
    void everyRuleMasksItsPositiveSamplesAndLeavesTheNegativesAlone() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(FIXTURE));
        for (JsonNode c : cases) {
            Pattern p = Pattern.compile(c.get("pattern").asText());
            String replacement = c.get("replacement").asText();
            for (JsonNode pos : c.get("positives")) {
                assertEquals(pos.get("output").asText(), p.matcher(pos.get("input").asText()).replaceAll(replacement),
                        c.get("rule").asText() + ": " + pos.get("input").asText());
            }
            for (JsonNode neg : c.get("negatives")) {
                assertEquals(neg.asText(), p.matcher(neg.asText()).replaceAll(replacement), c.get("rule").asText() + " must not touch: " + neg.asText());
            }
        }
    }

    @Test
    void defaultPatternsAvoidWhatGoRe2CannotCompile() {
        for (AgentConfig.RedactionRule r : RedactionDefaults.RULES) {
            assertNull(RedactionDefaults.validateCustomPattern(r.pattern()), r.rule());
        }
    }

    @Test
    void customPatternsAreCheckedForJavaValidityAndRe2Compatibility() {
        assertNull(RedactionDefaults.validateCustomPattern("order-\\d{6}"));
        for (String bad : new String[] {null, "", " ", "(", "(?=look)ahead", "(?<!x)y", "(a)\\1", "a*+", "a++", "(?>atomic)", "x".repeat(501), "\\k<name>"}) {
            assertNotNull(RedactionDefaults.validateCustomPattern(bad), String.valueOf(bad));
        }
    }

    @Test
    void agentConfigAddsCustomRulesToTheDefaultsAndNeverReplacesThem() {
        XpLogSourceRepository repo = mock(XpLogSourceRepository.class);
        XpLogSource custom = new XpLogSource();
        custom.setKind("CUSTOM");
        custom.setEnabled(true);
        custom.setPathsJson("[\"/var/log/app/*.log\"]");
        custom.setParser("json");
        custom.setRedactionJson("[{\"rule\":\"order-id\",\"pattern\":\"order-\\\\d{6}\"}]");
        XpLogSource off = new XpLogSource();
        off.setKind("DOCKER");
        off.setEnabled(false);
        when(repo.findByCloudAccountId(7L)).thenReturn(List.of(custom, off));
        XamPulseProperties props = new XamPulseProperties();
        props.getGateway().setPublicUrl("https://live.example.com/");
        XpAgent agent = new XpAgent();
        agent.setCloudAccountId(7L);
        agent.setCollectionLevel("T2");
        agent.setConfigVersion(4);

        AgentConfig cfg = new AgentConfigService(repo, props).render(agent);

        assertEquals(4, cfg.configVersion());
        assertEquals("T2", cfg.level());
        assertEquals("https://live.example.com/xampulse-ingest", cfg.gatewayUrl());
        assertEquals(1, cfg.sources().size(), "disabled sources are not sent");
        assertEquals("CUSTOM", cfg.sources().get(0).kind());
        assertEquals(List.of("/var/log/app/*.log"), cfg.sources().get(0).paths());
        assertEquals(RedactionDefaults.RULES.size() + 1, cfg.redaction().size());
        assertEquals("order-id", cfg.redaction().get(cfg.redaction().size() - 1).rule());
        assertTrue(cfg.redaction().containsAll(RedactionDefaults.RULES));
    }

    @Test
    void anAccountWithNoSourcesGetsSystemLogsAndAnUnknownLevelFallsBackToT1() {
        XpLogSourceRepository repo = mock(XpLogSourceRepository.class);
        when(repo.findByCloudAccountId(7L)).thenReturn(List.of());
        XpAgent agent = new XpAgent();
        agent.setCloudAccountId(7L);
        agent.setCollectionLevel("T9");
        AgentConfig cfg = new AgentConfigService(repo, new XamPulseProperties()).render(agent);
        assertEquals("T1", cfg.level());
        assertEquals("SYSTEM", cfg.sources().get(0).kind());
        assertEquals(1, cfg.configVersion());
        assertEquals("", cfg.gatewayUrl());
    }

    @Test
    void etagChangesWithTheConfig() {
        XpLogSourceRepository repo = mock(XpLogSourceRepository.class);
        when(repo.findByCloudAccountId(7L)).thenReturn(List.of());
        AgentConfigService svc = new AgentConfigService(repo, new XamPulseProperties());
        XpAgent a = new XpAgent();
        a.setCloudAccountId(7L);
        a.setConfigVersion(1);
        String one = AgentConfigService.etag(svc.render(a));
        a.setConfigVersion(2);
        assertNotEquals(one, AgentConfigService.etag(svc.render(a)));
        assertTrue(one.startsWith("\"") && one.endsWith("\""));
    }
}
