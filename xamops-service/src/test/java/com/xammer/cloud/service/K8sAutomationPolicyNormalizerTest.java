package com.xammer.cloud.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.config.K8sReadinessKey;
import com.xammer.cloud.dto.autoscaling.AutomationPolicyBundle;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.EvictorPolicyDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.NodeTemplateDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.RebalancerConfigDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.SafetyGuardsDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.ScaleOutPolicyDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.WorkloadRightsizerPolicyDto;
import com.xammer.cloud.dto.autoscaling.ConsolidationPolicyDto;
import com.xammer.cloud.dto.autoscaling.HpaManagerPolicyDto;
import com.xammer.cloud.dto.autoscaling.NodeOptimizerPolicyDto;
import com.xammer.cloud.dto.autoscaling.VpaManagerPolicyDto;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer.Section;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer.UiPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The policy normalizer (design §6.6, §6.7, §13, §17, NIT 11). Every coercion row is tested on
 * both serve paths, and again with its readiness key flipped to ready.
 */
class K8sAutomationPolicyNormalizerTest {

    private static final long CONFIG = 100L;
    private static final ObjectMapper RAW = new ObjectMapper();

    private K8sAutomationReadinessProperties readiness;
    private K8sAutomationPolicyNormalizer normalizer;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        readiness = new K8sAutomationReadinessProperties();
        normalizer = new K8sAutomationPolicyNormalizer(readiness);
        logger = (Logger) LoggerFactory.getLogger(K8sAutomationPolicyNormalizer.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** A stored bundle as older code could have written it: every section present, then mutated. */
    private static String stored(Consumer<AutomationPolicyBundle> mutate) throws Exception {
        AutomationPolicyBundle b = new AutomationPolicyBundle();
        b.setAutoscalerMode("full");
        b.setScaleOutPolicy(new ScaleOutPolicyDto());
        b.setEvictorPolicy(new EvictorPolicyDto());
        WorkloadRightsizerPolicyDto wr = new WorkloadRightsizerPolicyDto();
        wr.setEnabled(false);
        b.setWorkloadRightsizerPolicy(wr);
        b.setHpaManagerPolicy(new HpaManagerPolicyDto());
        b.setVpaManagerPolicy(new VpaManagerPolicyDto());
        b.setNodeOptimizerPolicy(new NodeOptimizerPolicyDto());
        b.setNodeTemplates(new ArrayList<>());
        RebalancerConfigDto r = new RebalancerConfigDto();
        r.setEnabled(false);
        b.setRebalancer(r);
        SafetyGuardsDto g = new SafetyGuardsDto();
        g.setCriticalNamespaces(new ArrayList<>(List.of("kube-system", "xamops-system")));
        b.setSafetyGuards(g);
        mutate.accept(b);
        return RAW.writeValueAsString(b);
    }

    private List<AutomationPolicyBundle> bothPaths(String json) {
        return List.of(normalizer.forAgent(json, CONFIG, true), normalizer.forUi(json, CONFIG, null).bundle());
    }

    private void ready(K8sReadinessKey... keys) {
        for (K8sReadinessKey key : keys) {
            switch (key) {
                case VERTICAL_RIGHTSIZING -> readiness.setVerticalRightsizing(true);
                case HPA_APPLY -> readiness.setHpaApply(true);
                case VPA_APPLY -> readiness.setVpaApply(true);
                case EVICTOR_LIVE -> readiness.setEvictorLive(true);
                case REBALANCER -> readiness.setRebalancer(true);
                case NODE_OPTIMIZER_ACTIONS -> readiness.setNodeOptimizerActions(true);
                case SCALE_OUT -> readiness.setScaleOut(true);
                case NODE_TEMPLATES -> readiness.setNodeTemplates(true);
                case SPOT_INTERRUPTION_ACTIONS -> readiness.setSpotInterruptionActions(true);
                case CONSOLIDATION_ACTIONS -> readiness.setConsolidationActions(true);
            }
        }
    }

    private static K8sAutomationRefusedException refusal(Runnable call) {
        try {
            call.run();
        } catch (K8sAutomationRefusedException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    private List<ILoggingEvent> at(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    // ── Completeness ──────────────────────────────────────────────────────

    @Test
    void defaultsAreCompleteAndAnalysisOnly() throws Exception {
        AutomationPolicyBundle d = normalizer.defaults();
        assertThat(d.getAutoscalerMode()).isEqualTo("full");
        // Every controller starts off, as the automation agent's own DefaultBundle does (review S1).
        assertThat(d.getScaleOutPolicy().isEnabled()).isFalse();
        assertThat(d.getEvictorPolicy().isEnabled()).isFalse();
        assertThat(d.getEvictorPolicy().isAnalyseOnly()).isTrue();
        assertThat(d.getWorkloadRightsizerPolicy().isEnabled()).isFalse();
        assertThat(d.getWorkloadRightsizerPolicy().getApplyMode()).isEqualTo("deferred");
        assertThat(d.getHpaManagerPolicy().isEnabled()).isFalse();
        assertThat(d.getHpaManagerPolicy().isAnalyseOnly()).isTrue();
        assertThat(d.getHpaManagerPolicy().getDefaultMinReplicas()).isEqualTo(1);
        assertThat(d.getHpaManagerPolicy().getDefaultMaxReplicas()).isEqualTo(10);
        assertThat(d.getHpaManagerPolicy().getDefaultTargetCpuPct()).isEqualTo(80);
        assertThat(d.getVpaManagerPolicy().isEnabled()).isFalse();
        assertThat(d.getVpaManagerPolicy().isAnalyseOnly()).isTrue();
        assertThat(d.getVpaManagerPolicy().getUpdateMode()).isEqualTo("Off");
        assertThat(d.getNodeOptimizerPolicy().isAutoCordonIdleNodes()).isFalse();
        assertThat(d.getNodeOptimizerPolicy().isAutoDrainIdleNodes()).isFalse();
        assertThat(d.getNodeOptimizerPolicy().getMaxParallelDrains()).isEqualTo(1);
        assertThat(d.getNodeTemplates()).isEmpty();
        assertThat(d.getRebalancer().getStatus()).isEqualTo("idle");
        assertThat(d.getRebalancer().isEnabled()).isFalse();
        assertThat(d.getRebalancer().isScheduleEnabled()).isFalse();
        assertThat(d.getSafetyGuards().getCriticalNamespaces())
                .containsExactly("kube-system", "monitoring", "ingress-nginx", "xamops-system");

        JsonNode json = RAW.readTree(normalizer.toJson(d));
        assertThat(json.fieldNames()).toIterable().contains("autoscalerMode", "scaleOutPolicy", "evictorPolicy",
                "workloadRightsizerPolicy", "hpaManagerPolicy", "vpaManagerPolicy", "nodeOptimizerPolicy",
                "nodeTemplates", "rebalancer", "safetyGuards");
        assertThat(logs.list).isEmpty();
    }

    @Test
    void aPartialStoredBundleIsServedComplete() {
        String partial = "{\"autoscalerMode\":\"scoped\",\"evictorPolicy\":{\"enabled\":true,\"analyseOnly\":true},"
                + "\"safetyGuards\":null}";
        for (AutomationPolicyBundle b : bothPaths(partial)) {
            assertThat(b.getAutoscalerMode()).isEqualTo("scoped");
            assertThat(b.getHpaManagerPolicy()).isNotNull();
            assertThat(b.getVpaManagerPolicy()).isNotNull();
            assertThat(b.getRebalancer()).isNotNull();
            assertThat(b.getNodeOptimizerPolicy()).isNotNull();
            assertThat(b.getNodeTemplates()).isNotNull();
            assertThat(b.getSafetyGuards().getCriticalNamespaces()).contains("kube-system", "xamops-system");
        }
        String nullNamespaces = "{\"safetyGuards\":{\"criticalNamespaces\":null}}";
        assertThat(normalizer.forAgent(nullNamespaces, CONFIG, true).getSafetyGuards().getCriticalNamespaces())
                .containsExactly("kube-system", "monitoring", "ingress-nginx", "xamops-system");
    }

    @Test
    void aCreatedSectionStartsOff_aStoredOneKeepsItsValue() {
        // Review S1: an agent image older than 03481bb4f gates the evictor on enabled alone, so a
        // section the normalizer adds must not switch a controller on.
        // The VPA manager's stored enabled is kept only once vpa-apply is ready: while it is not
        // ready the serve-time coercion forces enabled:false (FEAT-001's inert posture), which the
        // vpaApplyRow test pins. Here the concern is that fillMissing keeps a stored value, so the
        // readiness key is set to isolate that from the coercion.
        ready(K8sReadinessKey.VPA_APPLY);
        String storedEvictorOnly = "{\"autoscalerMode\":\"full\",\"evictorPolicy\":{\"enabled\":true,\"analyseOnly\":true},"
                + "\"vpaManagerPolicy\":{\"enabled\":true,\"analyseOnly\":true,\"updateMode\":\"Off\"}}";
        for (AutomationPolicyBundle b : bothPaths(storedEvictorOnly)) {
            assertThat(b.getEvictorPolicy().isEnabled()).as("stored evictor").isTrue();
            assertThat(b.getVpaManagerPolicy().isEnabled()).as("stored VPA manager").isTrue();
            assertThat(b.getScaleOutPolicy().isEnabled()).as("created scale-out").isFalse();
        }
        for (AutomationPolicyBundle b : bothPaths("{\"autoscalerMode\":\"full\"}")) {
            assertThat(b.getEvictorPolicy().isEnabled()).as("created evictor").isFalse();
            assertThat(b.getVpaManagerPolicy().isEnabled()).as("created VPA manager").isFalse();
            assertThat(b.getScaleOutPolicy().isEnabled()).as("created scale-out").isFalse();
        }
    }

    @Test
    void noStoredPolicyIsTheCompleteDefault_notTheOldPartialFallback() {
        AutomationPolicyBundle agent = normalizer.forAgent(null, CONFIG, true);
        assertThat(agent.getAutoscalerMode()).isEqualTo("full");
        assertThat(agent.getSafetyGuards()).isNotNull();
        UiPolicy ui = normalizer.forUi(null, CONFIG, null);
        assertThat(ui.policySource()).isEqualTo("default");
        assertThat(normalizer.forUi("{}", CONFIG, null).policySource()).isEqualTo("stored");
    }

    @Test
    void unknownPropertiesAreIgnored() {
        AutomationPolicyBundle b = normalizer.forAgent(
                "{\"autoscalerMode\":\"full\",\"futureSection\":{\"x\":1},\"evictorPolicy\":{\"newKnob\":3}}", CONFIG, true);
        assertThat(b.getAutoscalerMode()).isEqualTo("full");
        assertThat(b.getEvictorPolicy()).isNotNull();
    }

    @Test
    void anUnknownModeIsServedDisabled() {
        assertThat(normalizer.forAgent("{\"autoscalerMode\":\"turbo\"}", CONFIG, true).getAutoscalerMode())
                .isEqualTo("disabled");
    }

    @Test
    void kubeSystemAndXamopsSystemAreAlwaysProtected() throws Exception {
        String json = stored(b -> b.getSafetyGuards().setCriticalNamespaces(new ArrayList<>(List.of("monitoring"))));
        ready(K8sReadinessKey.values());
        for (AutomationPolicyBundle b : bothPaths(json)) {
            assertThat(b.getSafetyGuards().getCriticalNamespaces())
                    .containsExactly("monitoring", "kube-system", "xamops-system");
        }
    }

    // ── Coercion table, one row at a time ─────────────────────────────────

    private void assertRow(Consumer<AutomationPolicyBundle> violate, Function<AutomationPolicyBundle, Object> read,
                           Object safe, Object storedValue, K8sReadinessKey key) throws Exception {
        String json = stored(violate);
        for (AutomationPolicyBundle b : bothPaths(json)) {
            assertThat(read.apply(b)).as("served while " + key + " isn't ready").isEqualTo(safe);
        }
        ready(key);
        for (AutomationPolicyBundle b : bothPaths(json)) {
            assertThat(read.apply(b)).as("served once " + key + " is ready").isEqualTo(storedValue);
        }
    }

    @Test
    void evictorLiveRow() throws Exception {
        assertRow(b -> b.getEvictorPolicy().setAnalyseOnly(false), b -> b.getEvictorPolicy().isAnalyseOnly(),
                true, false, K8sReadinessKey.EVICTOR_LIVE);
    }

    @Test
    void hpaApplyRow() throws Exception {
        assertRow(b -> b.getHpaManagerPolicy().setAnalyseOnly(false), b -> b.getHpaManagerPolicy().isAnalyseOnly(),
                true, false, K8sReadinessKey.HPA_APPLY);
    }

    @Test
    void vpaApplyRow() throws Exception {
        // The enabled:false assertion is the load-bearing one for FEAT-001: VpaManagerPolicyDto
        // defaults enabled=true, so the serve path must coerce it false while vpa-apply isn't ready.
        assertRow(b -> {
                    b.getVpaManagerPolicy().setEnabled(true);
                    b.getVpaManagerPolicy().setAnalyseOnly(false);
                    b.getVpaManagerPolicy().setUpdateMode("Auto");
                },
                b -> b.getVpaManagerPolicy().isEnabled() + "/" + b.getVpaManagerPolicy().isAnalyseOnly()
                        + "/" + b.getVpaManagerPolicy().getUpdateMode(),
                "false/true/Off", "true/false/Auto", K8sReadinessKey.VPA_APPLY);
    }

    @Test
    void vpaApplyServedDisabledRoundTripsSoRunRecordsDeserialize() throws Exception {
        // A stored bundle with the VPA manager fully live (enabled:true, analyseOnly:false,
        // updateMode:Auto) is served inert while vpa-apply isn't ready, and the served bundle must
        // still serialize/deserialize so run records built from it round-trip.
        String json = stored(b -> {
            b.getVpaManagerPolicy().setEnabled(true);
            b.getVpaManagerPolicy().setAnalyseOnly(false);
            b.getVpaManagerPolicy().setUpdateMode("Auto");
        });
        for (AutomationPolicyBundle served : bothPaths(json)) {
            assertThat(served.getVpaManagerPolicy().isEnabled()).as("served enabled:false").isFalse();
            assertThat(served.getVpaManagerPolicy().isAnalyseOnly()).isTrue();
            assertThat(served.getVpaManagerPolicy().getUpdateMode()).isEqualTo("Off");

            String roundTrip = normalizer.toJson(served);
            AutomationPolicyBundle back = RAW.readValue(roundTrip, AutomationPolicyBundle.class);
            assertThat(back.getVpaManagerPolicy().isEnabled()).isFalse();
            assertThat(back.getVpaManagerPolicy().isAnalyseOnly()).isTrue();
            assertThat(back.getVpaManagerPolicy().getUpdateMode()).isEqualTo("Off");
        }
    }

    @Test
    void nodeOptimizerActionsRow() throws Exception {
        assertRow(b -> {
                    b.getNodeOptimizerPolicy().setAutoCordonIdleNodes(true);
                    b.getNodeOptimizerPolicy().setAutoDrainIdleNodes(true);
                    b.getNodeOptimizerPolicy().setSpotRatioTargetPct(60);
                },
                b -> b.getNodeOptimizerPolicy().isAutoCordonIdleNodes() + "/"
                        + b.getNodeOptimizerPolicy().isAutoDrainIdleNodes() + "/"
                        + b.getNodeOptimizerPolicy().getSpotRatioTargetPct(),
                "false/false/0", "true/true/60", K8sReadinessKey.NODE_OPTIMIZER_ACTIONS);
    }

    @Test
    void rebalancerRow_runningIsServedIdle() throws Exception {
        assertRow(b -> {
                    b.getRebalancer().setStatus("running");
                    b.getRebalancer().setScheduleEnabled(true);
                    b.getRebalancer().setEnabled(true);
                },
                b -> b.getRebalancer().getStatus() + "/" + b.getRebalancer().isScheduleEnabled() + "/"
                        + b.getRebalancer().isEnabled(),
                "idle/false/false", "running/true/true", K8sReadinessKey.REBALANCER);
    }

    @Test
    void lockedGuardrailRow_appliesEvenWithEveryKeyReady() throws Exception {
        String json = stored(b -> {
            b.getNodeOptimizerPolicy().setMaxParallelDrains(4);
            b.getSafetyGuards().setRespectPodDisruptionBudgets(false);
            b.getSafetyGuards().setProtectCriticalNamespaces(false);
            b.getSafetyGuards().setCriticalNamespaces(new ArrayList<>(List.of("payments")));
        });
        ready(K8sReadinessKey.values());
        for (AutomationPolicyBundle b : bothPaths(json)) {
            assertThat(b.getNodeOptimizerPolicy().getMaxParallelDrains()).isEqualTo(1);
            assertThat(b.getSafetyGuards().isRespectPodDisruptionBudgets()).isTrue();
            assertThat(b.getSafetyGuards().isProtectCriticalNamespaces()).isTrue();
            assertThat(b.getSafetyGuards().getCriticalNamespaces())
                    .containsExactly("payments", "kube-system", "xamops-system");
        }
    }

    @Test
    void goRightsizerIsServedDisabledEvenWithEveryKeyReady() throws Exception {
        String json = stored(b -> {
            b.getWorkloadRightsizerPolicy().setEnabled(true);
            b.getWorkloadRightsizerPolicy().setApplyMode("immediate");
        });
        ready(K8sReadinessKey.values());
        for (AutomationPolicyBundle b : bothPaths(json)) {
            assertThat(b.getWorkloadRightsizerPolicy().isEnabled()).isFalse();
            assertThat(b.getWorkloadRightsizerPolicy().getApplyMode()).isEqualTo("deferred");
        }
    }

    @Test
    void scaleOutAndNodeTemplatesAreServedUnchanged() throws Exception {
        NodeTemplateDto t = new NodeTemplateDto();
        t.setId("tpl-1");
        t.setName("spot pool");
        String json = stored(b -> {
            b.getScaleOutPolicy().setEnabled(true);
            b.getScaleOutPolicy().setHeadroomCpuPercent(33);
            b.setNodeTemplates(new ArrayList<>(List.of(t)));
        });
        for (AutomationPolicyBundle b : bothPaths(json)) {
            assertThat(b.getScaleOutPolicy().getHeadroomCpuPercent()).isEqualTo(33);
            assertThat(b.getNodeTemplates()).extracting(NodeTemplateDto::getId).containsExactly("tpl-1");
        }
    }

    @Test
    void spotInterruptionActionsRow() throws Exception {
        String json = stored(b -> b.getNodeOptimizerPolicy().setHandleSpotInterruptions(true));
        ready(K8sReadinessKey.NODE_OPTIMIZER_ACTIONS);
        for (AutomationPolicyBundle b : bothPaths(json)) {
            assertThat(b.getNodeOptimizerPolicy().isHandleSpotInterruptions())
                    .as("node-optimizer-actions alone does not open interruption handling").isFalse();
        }
        assertRow(b -> b.getNodeOptimizerPolicy().setHandleSpotInterruptions(true),
                b -> b.getNodeOptimizerPolicy().isHandleSpotInterruptions(),
                false, true, K8sReadinessKey.SPOT_INTERRUPTION_ACTIONS);
    }
    @Test
    void aStoredBundleWithoutHandleSpotInterruptionsIsServedFalse() throws Exception {
        JsonNode tree = RAW.readTree(stored(b -> { }));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get("nodeOptimizerPolicy")).remove("handleSpotInterruptions");
        String json = RAW.writeValueAsString(tree);
        assertThat(json).doesNotContain("handleSpotInterruptions");
        ready(K8sReadinessKey.values());
        for (AutomationPolicyBundle b : bothPaths(json)) {
            assertThat(b.getNodeOptimizerPolicy().isHandleSpotInterruptions()).isFalse();
        }
    }
    @Test
    void defaultsServeHandleSpotInterruptionsFalse() {
        assertThat(normalizer.defaults().getNodeOptimizerPolicy().isHandleSpotInterruptions()).isFalse();
        assertThat(new NodeOptimizerPolicyDto().isHandleSpotInterruptions()).isFalse();
    }
    @Test
    void spotInterruptionHandlingIsRefusedWhileNotReady() {
        NodeOptimizerPolicyDto p = new NodeOptimizerPolicyDto();
        p.setHandleSpotInterruptions(true);
        ready(K8sReadinessKey.NODE_OPTIMIZER_ACTIONS);
        K8sAutomationRefusedException e = refusal(() -> normalizer.validate(Section.NODE_OPTIMIZER, p));
        assertThat(e.getReason()).isEqualTo(K8sAutomationReason.CONTROLLER_NOT_READY);
        assertThat(e.getField()).isEqualTo("handleSpotInterruptions");
        assertThat(e.getMessage()).isEqualTo(K8sReadinessKey.SPOT_INTERRUPTION_ACTIONS.message());
        ready(K8sReadinessKey.SPOT_INTERRUPTION_ACTIONS);
        assertThatCode(() -> normalizer.validate(Section.NODE_OPTIMIZER, p)).doesNotThrowAnyException();
    }

    // ── Logging ───────────────────────────────────────────────────────────

    @Test
    void theCoercionWarnIsLoggedOncePerFieldSet_andAgainWhenTheSetChanges() throws Exception {
        String running = stored(b -> b.getRebalancer().setStatus("running"));
        for (int i = 0; i < 5; i++) {
            normalizer.forAgent(running, CONFIG, true);
        }
        assertThat(at(Level.WARN)).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("100", "rebalancer.status"));

        String runningAndLive = stored(b -> {
            b.getRebalancer().setStatus("running");
            b.getEvictorPolicy().setAnalyseOnly(false);
        });
        normalizer.forAgent(runningAndLive, CONFIG, true);
        normalizer.forUi(runningAndLive, CONFIG, null);
        assertThat(at(Level.WARN)).hasSize(2);
        assertThat(at(Level.WARN).get(1).getFormattedMessage()).contains("evictorPolicy.analyseOnly");

        normalizer.forAgent(running, 101L, true);
        assertThat(at(Level.WARN)).hasSize(3);
    }

    @Test
    void theLogMemoryIsCappedAndClearedWhenFull() throws Exception {
        String running = stored(b -> b.getRebalancer().setStatus("running"));
        for (long id = 0; id < K8sAutomationPolicyNormalizer.LOG_DEDUP_CAP; id++) {
            normalizer.forAgent(running, id, true);
        }
        assertThat(normalizer.loggedKeyCount()).isEqualTo(K8sAutomationPolicyNormalizer.LOG_DEDUP_CAP);
        normalizer.forAgent(running, 5_000L, true);
        assertThat(normalizer.loggedKeyCount()).isEqualTo(1);
    }

    // ── Unparseable and owner-blocked bundles ─────────────────────────────

    @Test
    void unparseableJsonIsServedAsTheDisabledDefault_withOneErrorPerConfig() {
        for (String broken : List.of("{not json", "[1,2]", "null")) {
            AutomationPolicyBundle agent = normalizer.forAgent(broken, CONFIG, true);
            assertThat(agent.getAutoscalerMode()).as(broken).isEqualTo("disabled");
            assertThat(agent.getSafetyGuards()).isNotNull();
            UiPolicy ui = normalizer.forUi(broken, CONFIG, null);
            assertThat(ui.bundle().getAutoscalerMode()).isEqualTo("disabled");
            assertThat(ui.policySource()).isEqualTo("default");
        }
        assertThat(at(Level.ERROR)).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).contains("100");
            assertThat(e.getFormattedMessage()).doesNotContain("not json");
        });
        assertThat(normalizer.readForWrite("{not json", CONFIG).getAutoscalerMode()).isEqualTo("disabled");
    }

    @Test
    void aReadOnlyOrUnverifiedOwnerGetsTheCompleteDisabledDefault() throws Exception {
        String full = stored(b -> b.setAutoscalerMode("full"));
        AutomationPolicyBundle served = normalizer.forAgent(full, CONFIG, false);
        assertThat(served.getAutoscalerMode()).isEqualTo("disabled");
        assertThat(served.getEvictorPolicy()).isNotNull();
        assertThat(served.getSafetyGuards().getCriticalNamespaces()).contains("kube-system", "xamops-system");
        normalizer.forAgent(full, CONFIG, false);
        assertThat(at(Level.WARN)).singleElement().satisfies(e -> assertThat(e.getFormattedMessage()).contains("100"));
    }

    @Test
    void theUiIsToldWhyTheAgentPolicyIsBlocked() throws Exception {
        String full = stored(b -> b.setAutoscalerMode("full"));
        UiPolicy readOnly = normalizer.forUi(full, CONFIG, K8sAutomationReason.READ_ONLY_CONNECTION);
        assertThat(readOnly.agentPolicyBlockedBy()).isEqualTo(K8sAutomationReason.READ_ONLY_CONNECTION);
        assertThat(readOnly.bundle().getAutoscalerMode()).isEqualTo("full");
        assertThat(readOnly.policySource()).isEqualTo("stored");
        assertThat(normalizer.forUi(full, CONFIG, K8sAutomationReason.CONNECTION_NOT_VERIFIED).agentPolicyBlockedBy())
                .isEqualTo(K8sAutomationReason.CONNECTION_NOT_VERIFIED);
        assertThat(normalizer.forUi(full, CONFIG, null).agentPolicyBlockedBy()).isNull();
    }

    // ── Write path ────────────────────────────────────────────────────────

    @Test
    void aSafetyGuardsSaveWithNullNamespacesKeepsTheStoredList() throws Exception {
        AutomationPolicyBundle current = normalizer.readForWrite(stored(b -> b.getSafetyGuards()
                .setCriticalNamespaces(new ArrayList<>(List.of("payments", "kube-system", "xamops-system")))), CONFIG);
        SafetyGuardsDto body = new SafetyGuardsDto();
        body.setProtectSingleReplicas(false);
        body.setCriticalNamespaces(null);

        AutomationPolicyBundle next = normalizer.replaceSection(current, Section.SAFETY_GUARDS, body);

        assertThat(next.getSafetyGuards().getCriticalNamespaces()).containsExactly("payments", "kube-system", "xamops-system");
        assertThat(next.getSafetyGuards().isProtectSingleReplicas()).isFalse();
        assertThat(current.getSafetyGuards().isProtectSingleReplicas()).as("current is not modified").isTrue();
    }

    @Test
    void anExplicitNamespaceListIsCleanedAndKeepsTheRequiredOnes() {
        SafetyGuardsDto body = new SafetyGuardsDto();
        body.setCriticalNamespaces(new ArrayList<>(List.of(" Payments ", "payments", "")));
        AutomationPolicyBundle next = normalizer.replaceSection(normalizer.defaults(), Section.SAFETY_GUARDS, body);
        assertThat(next.getSafetyGuards().getCriticalNamespaces()).containsExactly("payments", "kube-system", "xamops-system");
    }

    @Test
    void aRebalancerSaveKeepsServerOwnedFields_andTheServedStatusStaysIdle() throws Exception {
        AutomationPolicyBundle current = new ObjectMapper().readValue(stored(b -> {
            b.getRebalancer().setLastRebalanceAt("2026-10-01T02:00:00Z");
            b.getRebalancer().setLastRebalanceDuration(14);
            b.getRebalancer().setStatus("completed");
        }), AutomationPolicyBundle.class);
        RebalancerConfigDto body = new RebalancerConfigDto();
        body.setStatus("running");
        body.setLastRebalanceAt("1999-01-01T00:00:00Z");
        body.setLastRebalanceDuration(999);
        body.setDrainTimeoutMinutes(30);

        AutomationPolicyBundle next = normalizer.replaceSection(current, Section.REBALANCER, body);
        assertThat(next.getRebalancer().getStatus()).isEqualTo("completed");
        assertThat(next.getRebalancer().getLastRebalanceAt()).isEqualTo("2026-10-01T02:00:00Z");
        assertThat(next.getRebalancer().getLastRebalanceDuration()).isEqualTo(14);
        assertThat(next.getRebalancer().getDrainTimeoutMinutes()).isEqualTo(30);
        assertThat(normalizer.normalize(next, CONFIG).getRebalancer().getStatus()).isEqualTo("idle");
    }

    @Test
    void everySectionReplaceProducesACompleteBundle() {
        AutomationPolicyBundle start = normalizer.readForWrite("{\"autoscalerMode\":\"full\"}", CONFIG);
        List<Object[]> saves = List.of(
                new Object[] {Section.SCALE_OUT, new ScaleOutPolicyDto()},
                new Object[] {Section.EVICTOR, new EvictorPolicyDto()},
                new Object[] {Section.NODE_OPTIMIZER, new NodeOptimizerPolicyDto()},
                new Object[] {Section.SAFETY_GUARDS, new SafetyGuardsDto()},
                new Object[] {Section.REBALANCER, new RebalancerConfigDto()},
                new Object[] {Section.MODE, "scoped"});
        for (Object[] save : saves) {
            AutomationPolicyBundle next = normalizer.normalize(
                    normalizer.replaceSection(start, (Section) save[0], save[1]), CONFIG);
            assertThat(next.getSafetyGuards().getCriticalNamespaces()).as(save[0].toString()).isNotEmpty()
                    .contains("kube-system", "xamops-system");
            assertThat(next.getHpaManagerPolicy()).isNotNull();
            assertThat(next.getVpaManagerPolicy()).isNotNull();
            assertThat(next.getRebalancer()).isNotNull();
            assertThat(next.getEvictorPolicy()).isNotNull();
        }
    }

    @Test
    void nodeTemplatesUpsertAndDeleteById() {
        NodeTemplateDto t = new NodeTemplateDto();
        t.setName("pool");
        AutomationPolicyBundle withOne = normalizer.upsertNodeTemplate(normalizer.defaults(), "tpl-a", t);
        AutomationPolicyBundle replaced = normalizer.upsertNodeTemplate(withOne, "tpl-a", t);
        assertThat(replaced.getNodeTemplates()).extracting(NodeTemplateDto::getId).containsExactly("tpl-a");
        assertThat(normalizer.deleteNodeTemplate(replaced, "tpl-a").getNodeTemplates()).isEmpty();
    }

    // ── Validation order: readiness, then locked guardrails, then ranges ──

    @Test
    void evictorLiveIsRefusedFirst_thenRanges() {
        EvictorPolicyDto live = new EvictorPolicyDto();
        live.setAnalyseOnly(false);
        live.setTargetUtilizationCpu(200);
        K8sAutomationRefusedException e = refusal(() -> normalizer.validate(Section.EVICTOR, live));
        assertThat(e.getReason()).isEqualTo(K8sAutomationReason.ANALYSE_ONLY);
        assertThat(e.getStatus().value()).isEqualTo(403);
        assertThat(e.getMessage()).isEqualTo(K8sReadinessKey.EVICTOR_LIVE.message());

        EvictorPolicyDto range = new EvictorPolicyDto();
        range.setTargetUtilizationCpu(96);
        K8sAutomationRefusedException r = refusal(() -> normalizer.validate(Section.EVICTOR, range));
        assertThat(r.getReason()).isEqualTo(K8sAutomationReason.INVALID_POLICY);
        assertThat(r.getStatus().value()).isEqualTo(400);
        assertThat(r.getField()).isEqualTo("targetUtilizationCpu");

        for (int[] bad : new int[][] {{1, 9}, {2, 0}, {3, 1441}, {4, 29}, {4, 3601}}) {
            EvictorPolicyDto p = new EvictorPolicyDto();
            switch (bad[0]) {
                case 1 -> p.setTargetUtilizationMemory(bad[1]);
                case 2 -> p.setNodeGracePeriodMinutes(bad[1]);
                case 3 -> p.setNodeGracePeriodMinutes(bad[1]);
                default -> p.setNodeCycleIntervalSeconds(bad[1]);
            }
            assertThat(refusal(() -> normalizer.validate(Section.EVICTOR, p)).getStatus().value()).isEqualTo(400);
        }
        assertThatCode(() -> normalizer.validate(Section.EVICTOR, new EvictorPolicyDto())).doesNotThrowAnyException();
        ready(K8sReadinessKey.EVICTOR_LIVE);
        EvictorPolicyDto liveOk = new EvictorPolicyDto();
        liveOk.setAnalyseOnly(false);
        assertThatCode(() -> normalizer.validate(Section.EVICTOR, liveOk)).doesNotThrowAnyException();
    }

    @Test
    void nodeOptimizerActionsAreRefused_thenTheDrainLock_thenRanges() {
        NodeOptimizerPolicyDto actions = new NodeOptimizerPolicyDto();
        actions.setAutoDrainIdleNodes(true);
        actions.setMaxParallelDrains(3);
        K8sAutomationRefusedException a = refusal(() -> normalizer.validate(Section.NODE_OPTIMIZER, actions));
        assertThat(a.getReason()).isEqualTo(K8sAutomationReason.CONTROLLER_NOT_READY);
        assertThat(a.getField()).isEqualTo("autoDrainIdleNodes");
        assertThat(a.getMessage()).isEqualTo(K8sReadinessKey.NODE_OPTIMIZER_ACTIONS.message());

        NodeOptimizerPolicyDto spot = new NodeOptimizerPolicyDto();
        spot.setSpotRatioTargetPct(40);
        assertThat(refusal(() -> normalizer.validate(Section.NODE_OPTIMIZER, spot)).getField()).isEqualTo("spotRatioTargetPct");

        NodeOptimizerPolicyDto drains = new NodeOptimizerPolicyDto();
        drains.setMaxParallelDrains(3);
        drains.setIdleCpuThresholdPct(90);
        K8sAutomationRefusedException d = refusal(() -> normalizer.validate(Section.NODE_OPTIMIZER, drains));
        assertThat(d.getReason()).isEqualTo(K8sAutomationReason.GUARDRAIL_LOCKED);
        assertThat(d.getField()).isEqualTo("maxParallelDrains");

        NodeOptimizerPolicyDto idle = new NodeOptimizerPolicyDto();
        idle.setIdleMemThresholdPct(0);
        assertThat(refusal(() -> normalizer.validate(Section.NODE_OPTIMIZER, idle)).getField()).isEqualTo("idleMemThresholdPct");

        NodeOptimizerPolicyDto settings = new NodeOptimizerPolicyDto();
        settings.setHandleSpotInterruptions(false);
        settings.setIdleCpuThresholdPct(15);
        assertThatCode(() -> normalizer.validate(Section.NODE_OPTIMIZER, settings)).doesNotThrowAnyException();
    }

    @Test
    void safetyGuardLocksAndNamespaceRules() {
        SafetyGuardsDto pdb = new SafetyGuardsDto();
        pdb.setRespectPodDisruptionBudgets(false);
        K8sAutomationRefusedException l = refusal(() -> normalizer.validate(Section.SAFETY_GUARDS, pdb));
        assertThat(l.getReason()).isEqualTo(K8sAutomationReason.GUARDRAIL_LOCKED);
        assertThat(l.getField()).isEqualTo("respectPodDisruptionBudgets");

        SafetyGuardsDto critical = new SafetyGuardsDto();
        critical.setProtectCriticalNamespaces(false);
        assertThat(refusal(() -> normalizer.validate(Section.SAFETY_GUARDS, critical)).getField())
                .isEqualTo("protectCriticalNamespaces");

        SafetyGuardsDto bad = new SafetyGuardsDto();
        bad.setCriticalNamespaces(List.of("payments", "Not_Valid!"));
        K8sAutomationRefusedException b = refusal(() -> normalizer.validate(Section.SAFETY_GUARDS, bad));
        assertThat(b.getStatus().value()).isEqualTo(400);
        assertThat(b.getField()).isEqualTo("criticalNamespaces[1]");
        assertThat(b.getMessage()).doesNotContain("Not_Valid");

        SafetyGuardsDto tooMany = new SafetyGuardsDto();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add("ns-" + i);
        }
        tooMany.setCriticalNamespaces(many);
        assertThat(refusal(() -> normalizer.validate(Section.SAFETY_GUARDS, tooMany)).getField()).isEqualTo("criticalNamespaces");

        SafetyGuardsDto ok = new SafetyGuardsDto();
        ok.setCriticalNamespaces(List.of(" Payments "));
        assertThatCode(() -> normalizer.validate(Section.SAFETY_GUARDS, ok)).doesNotThrowAnyException();
    }

    @Test
    void rebalancerScheduleIsRefused_thenGracefulDrainingLock_thenRanges() {
        RebalancerConfigDto schedule = new RebalancerConfigDto();
        schedule.setScheduleEnabled(true);
        schedule.setGracefulDraining(false);
        K8sAutomationRefusedException s = refusal(() -> normalizer.validate(Section.REBALANCER, schedule));
        assertThat(s.getReason()).isEqualTo(K8sAutomationReason.CONTROLLER_NOT_READY);
        assertThat(s.getMessage()).isEqualTo(K8sReadinessKey.REBALANCER.message());

        RebalancerConfigDto graceful = new RebalancerConfigDto();
        graceful.setGracefulDraining(false);
        assertThat(refusal(() -> normalizer.validate(Section.REBALANCER, graceful)).getReason())
                .isEqualTo(K8sAutomationReason.GUARDRAIL_LOCKED);

        RebalancerConfigDto timeout = new RebalancerConfigDto();
        timeout.setDrainTimeoutMinutes(61);
        assertThat(refusal(() -> normalizer.validate(Section.REBALANCER, timeout)).getField()).isEqualTo("drainTimeoutMinutes");

        RebalancerConfigDto cron = new RebalancerConfigDto();
        cron.setScheduleCron("x".repeat(65));
        assertThat(refusal(() -> normalizer.validate(Section.REBALANCER, cron)).getField()).isEqualTo("scheduleCron");

        assertThatCode(() -> normalizer.validate(Section.REBALANCER, new RebalancerConfigDto())).doesNotThrowAnyException();
        assertThat(refusal(normalizer::validateRebalanceTrigger).getMessage()).isEqualTo(K8sReadinessKey.REBALANCER.message());
        ready(K8sReadinessKey.REBALANCER);
        assertThatCode(normalizer::validateRebalanceTrigger).doesNotThrowAnyException();
    }

    @Test
    void scaleOutAndNodeTemplatesAreRefusedWhileNotReady() {
        K8sAutomationRefusedException s = refusal(() -> normalizer.validate(Section.SCALE_OUT, new ScaleOutPolicyDto()));
        assertThat(s.getReason()).isEqualTo(K8sAutomationReason.CONTROLLER_NOT_READY);
        assertThat(s.getMessage()).isEqualTo(K8sReadinessKey.SCALE_OUT.message());

        K8sAutomationRefusedException t = refusal(() -> normalizer.validateNodeTemplate("bad id!"));
        assertThat(t.getReason()).as("readiness is checked before the id").isEqualTo(K8sAutomationReason.CONTROLLER_NOT_READY);
        assertThat(t.getMessage()).isEqualTo(K8sReadinessKey.NODE_TEMPLATES.message());

        ready(K8sReadinessKey.SCALE_OUT, K8sReadinessKey.NODE_TEMPLATES);
        assertThatCode(() -> normalizer.validate(Section.SCALE_OUT, new ScaleOutPolicyDto())).doesNotThrowAnyException();
        assertThat(refusal(() -> normalizer.validateNodeTemplate("bad id!")).getField()).isEqualTo("templateId");
        assertThatCode(() -> normalizer.validateNodeTemplate("tpl_spot-1")).doesNotThrowAnyException();
    }

    @Test
    void theGoRightsizerIsAlwaysRefused() {
        ready(K8sReadinessKey.values());
        K8sAutomationRefusedException e = refusal(() ->
                normalizer.validate(Section.WORKLOAD_RIGHTSIZER, new WorkloadRightsizerPolicyDto()));
        assertThat(e.getReason()).isEqualTo(K8sAutomationReason.CONTROLLER_NOT_READY);
        assertThat(e.getMessage()).isEqualTo("The automation agent's rightsizer is retired. "
                + "Workload rightsizing is configured in Workload autoscaling.");
    }

    @Test
    void modeMustBeAKnownValue() {
        for (String mode : List.of("full", "scoped", "disabled")) {
            assertThatCode(() -> normalizer.validate(Section.MODE, mode)).doesNotThrowAnyException();
        }
        K8sAutomationRefusedException e = refusal(() -> normalizer.validate(Section.MODE, "turbo"));
        assertThat(e.getReason()).isEqualTo(K8sAutomationReason.INVALID_POLICY);
        assertThat(e.getField()).isEqualTo("mode");
        assertThat(refusal(() -> normalizer.validate(Section.MODE, null)).getField()).isEqualTo("mode");
        assertThatThrownBy(() -> normalizer.replaceSection(normalizer.defaults(), Section.MODE, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Consolidation (FEAT-003) ──────────────────────────────────────────

    @Test
    void anOmittedConsolidationPolicyIsServedDisabledWithTheIntervalDefaults() {
        // No consolidationPolicy in the stored bundle: fillMissing seeds enabled=false / 600 / 1 / 600.
        ready(K8sReadinessKey.values());
        for (AutomationPolicyBundle b : bothPaths("{\"autoscalerMode\":\"full\"}")) {
            assertThat(b.getConsolidationPolicy()).isNotNull();
            assertThat(b.getConsolidationPolicy().isEnabled()).isFalse();
            assertThat(b.getConsolidationPolicy().getConsolidateAfterSeconds()).isEqualTo(600);
            assertThat(b.getConsolidationPolicy().getMaxConcurrentDisruptions()).isEqualTo(1);
            assertThat(b.getConsolidationPolicy().getDwellSeconds()).isEqualTo(600);
        }
        assertThat(normalizer.defaults().getConsolidationPolicy().isEnabled()).isFalse();
    }

    @Test
    void consolidationActionsRow_storedEnabledIsCoercedFalseWhileNotReady() throws Exception {
        String json = stored(b -> {
            ConsolidationPolicyDto p = new ConsolidationPolicyDto();
            p.setEnabled(true);
            b.setConsolidationPolicy(p);
        });
        for (AutomationPolicyBundle b : bothPaths(json)) {
            assertThat(b.getConsolidationPolicy().isEnabled())
                    .as("served with consolidation disabled while consolidation-actions isn't ready").isFalse();
        }
        ready(K8sReadinessKey.CONSOLIDATION_ACTIONS);
        for (AutomationPolicyBundle b : bothPaths(json)) {
            assertThat(b.getConsolidationPolicy().isEnabled())
                    .as("served enabled once consolidation-actions is ready").isTrue();
        }
    }

    @Test
    void consolidationEnabledWriteIsRefusedWhileNotReady_thenRanges() {
        ConsolidationPolicyDto on = new ConsolidationPolicyDto();
        on.setEnabled(true);
        K8sAutomationRefusedException e = refusal(() -> normalizer.validate(Section.CONSOLIDATION, on));
        assertThat(e.getReason()).isEqualTo(K8sAutomationReason.CONTROLLER_NOT_READY);
        assertThat(e.getField()).isEqualTo("enabled");
        assertThat(e.getMessage()).isEqualTo(K8sReadinessKey.CONSOLIDATION_ACTIONS.message());

        // A disabled policy with a bad interval is a range refusal, not a readiness one.
        ConsolidationPolicyDto badAfter = new ConsolidationPolicyDto();
        badAfter.setConsolidateAfterSeconds(59);
        assertThat(refusal(() -> normalizer.validate(Section.CONSOLIDATION, badAfter)).getField())
                .isEqualTo("consolidateAfterSeconds");
        ConsolidationPolicyDto badDisruptions = new ConsolidationPolicyDto();
        badDisruptions.setMaxConcurrentDisruptions(11);
        assertThat(refusal(() -> normalizer.validate(Section.CONSOLIDATION, badDisruptions)).getField())
                .isEqualTo("maxConcurrentDisruptions");
        ConsolidationPolicyDto badDwell = new ConsolidationPolicyDto();
        badDwell.setDwellSeconds(86401);
        assertThat(refusal(() -> normalizer.validate(Section.CONSOLIDATION, badDwell)).getField())
                .isEqualTo("dwellSeconds");

        // Defaults (disabled, 600/1/600) pass; enabled passes once the key is ready.
        assertThatCode(() -> normalizer.validate(Section.CONSOLIDATION, new ConsolidationPolicyDto()))
                .doesNotThrowAnyException();
        ready(K8sReadinessKey.CONSOLIDATION_ACTIONS);
        assertThatCode(() -> normalizer.validate(Section.CONSOLIDATION, on)).doesNotThrowAnyException();
    }

    @Test
    void replaceSectionConsolidationPersistsEnabledAndIsServedToTheAgentOnceReady() throws Exception {
        // Regression: replaceSection had no CONSOLIDATION case, so a save silently dropped the section
        // and the agent kept seeing consolidationPolicy.enabled=false even with the key ready.
        ready(K8sReadinessKey.CONSOLIDATION_ACTIONS);
        AutomationPolicyBundle current = normalizer.forUi("{\"autoscalerMode\":\"full\"}", CONFIG, null).bundle();

        ConsolidationPolicyDto on = new ConsolidationPolicyDto();
        on.setEnabled(true);
        AutomationPolicyBundle next = normalizer.replaceSection(current, Section.CONSOLIDATION, on);
        assertThat(next.getConsolidationPolicy()).isNotNull();
        assertThat(next.getConsolidationPolicy().isEnabled())
                .as("replaceSection must persist the consolidation section").isTrue();

        // And it survives the JSON round-trip the agent actually fetches.
        String storedJson = normalizer.toJson(next);
        assertThat(normalizer.forAgent(storedJson, CONFIG, true).getConsolidationPolicy().isEnabled())
                .as("the agent is served consolidationPolicy.enabled=true once consolidation-actions is ready")
                .isTrue();
    }
}
