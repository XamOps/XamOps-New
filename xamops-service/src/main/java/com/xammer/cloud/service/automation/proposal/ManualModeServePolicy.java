package com.xammer.cloud.service.automation.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.dto.autoscaling.AutomationPolicyBundle;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Layer 1 of Manual mode (design 3.3): source enforcement in the served policy.
 *
 * <p>Applied in {@code AgentController.getAutoscalingPolicy} right after
 * {@code policyNormalizer.forAgent(...)}, so the readiness coercion table has already run and
 * {@code manualIntent} already reflects readiness (an unready key is already safe in the intent).
 *
 * <ul>
 *   <li><strong>AUTONOMOUS</strong>: the normalizer's JSON, untouched and byte-identical to before
 *       (AC-B1): no new field.</li>
 *   <li><strong>MANUAL</strong>: the four acting sections are copied into {@code manualIntent}, then the
 *       served copy is neutered so an agent that knows nothing about Manual cannot act (every served
 *       gate lands at Off or Analyse); {@code approvalMode:"manual"} is added. {@code autoscalerMode} is
 *       passed through unchanged. The neutering does not depend on the {@code manual-approval} readiness
 *       key: turning that key off while a cluster is MANUAL must not re-enable autonomous actions.</li>
 * </ul>
 *
 * <p>An unreadable or non-AUTONOMOUS value other than MANUAL is treated as MANUAL (fail closed).
 */
@Component
public class ManualModeServePolicy {

    static final List<String> INTENT_SECTIONS =
            List.of("evictorPolicy", "nodeOptimizerPolicy", "rebalancer", "consolidationPolicy");

    private final K8sAutomationPolicyNormalizer normalizer;
    private final ManualBookkeeping bookkeeping;
    private final ObjectMapper mapper = new ObjectMapper();

    public ManualModeServePolicy(K8sAutomationPolicyNormalizer normalizer, ManualBookkeeping bookkeeping) {
        this.normalizer = normalizer;
        this.bookkeeping = bookkeeping;
    }

    /**
     * The JSON body to serve. Also records the serve bookkeeping for an automation-role principal on a
     * cluster that is or ever was Manual.
     */
    public String serve(KubernetesClusterConfig config, AgentPrincipal principal, AutomationPolicyBundle bundle) {
        String json = normalizer.toJson(bundle);
        bookkeeping.recordPolicyServed(config, principal);
        if (ApprovalModeReader.AUTONOMOUS.equals(config.getAutomationApprovalMode())) {
            return json;
        }
        return neuter(json);
    }

    /** The MANUAL transformation of an already-serialised, already-coerced bundle. */
    String neuter(String json) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(json);
            ObjectNode intent = mapper.createObjectNode();
            for (String section : INTENT_SECTIONS) {
                if (root.has(section) && root.get(section).isObject()) {
                    intent.set(section, root.get(section).deepCopy());
                }
            }
            section(root, "evictorPolicy").put("analyseOnly", true);
            ObjectNode node = section(root, "nodeOptimizerPolicy");
            node.put("autoCordonIdleNodes", false);
            node.put("autoDrainIdleNodes", false);
            node.put("spotRatioTargetPct", 0);
            node.put("handleSpotInterruptions", false);
            ObjectNode rebalancer = section(root, "rebalancer");
            rebalancer.put("enabled", false);
            rebalancer.put("scheduleEnabled", false);
            rebalancer.put("status", "idle");
            section(root, "consolidationPolicy").put("enabled", false);
            ObjectNode vpa = section(root, "vpaManagerPolicy");
            vpa.put("updateMode", "Off");
            vpa.put("analyseOnly", true);
            root.put("approvalMode", "manual");
            root.set("manualIntent", intent);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            // Fail closed: if the neutered copy cannot be built, serve the disabled default so the agent idles.
            return normalizer.toJson(normalizer.defaultsDisabled());
        }
    }

    private ObjectNode section(ObjectNode root, String name) {
        if (root.has(name) && root.get(name).isObject()) {
            return (ObjectNode) root.get(name);
        }
        return root.putObject(name);
    }
}
