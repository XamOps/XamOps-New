package com.xammer.cloud.dto.autoscaling;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.EvictorPolicyDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.NodeTemplateDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.RebalancerConfigDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.SafetyGuardsDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.ScaleOutPolicyDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.WorkloadRightsizerPolicyDto;

import java.util.List;

/**
 * Every configurable automation policy section, stored as one JSON object in
 * {@code agent_data_cache} ({@code dataType = "autoscaling-policy"}) and served to the automation
 * agent by {@code GET /api/v1/agent/autoscaling-policy}. Property names are the Go agent's
 * ({@code xamops-autoscaler/internal/policy/types.go}).
 *
 * <p>
 * Moved out of {@code EksAutoscalingService.PolicyBundle} with the same JSON shape, plus the two
 * sections the Go bundle already had ({@code hpaManagerPolicy}, {@code vpaManagerPolicy}).
 * {@code K8sAutomationPolicyNormalizer} owns completeness and safety; read it through the
 * normalizer, never straight from the stored JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AutomationPolicyBundle {

    /** full | scoped | disabled */
    private String autoscalerMode = "full";
    private ScaleOutPolicyDto scaleOutPolicy;
    private EvictorPolicyDto evictorPolicy;
    private WorkloadRightsizerPolicyDto workloadRightsizerPolicy;
    private HpaManagerPolicyDto hpaManagerPolicy;
    private VpaManagerPolicyDto vpaManagerPolicy;
    private NodeOptimizerPolicyDto nodeOptimizerPolicy;
    private List<NodeTemplateDto> nodeTemplates;
    private RebalancerConfigDto rebalancer;
    private SafetyGuardsDto safetyGuards;

    public String getAutoscalerMode() { return autoscalerMode; }
    public void setAutoscalerMode(String autoscalerMode) { this.autoscalerMode = autoscalerMode; }

    public ScaleOutPolicyDto getScaleOutPolicy() { return scaleOutPolicy; }
    public void setScaleOutPolicy(ScaleOutPolicyDto scaleOutPolicy) { this.scaleOutPolicy = scaleOutPolicy; }

    public EvictorPolicyDto getEvictorPolicy() { return evictorPolicy; }
    public void setEvictorPolicy(EvictorPolicyDto evictorPolicy) { this.evictorPolicy = evictorPolicy; }

    public WorkloadRightsizerPolicyDto getWorkloadRightsizerPolicy() { return workloadRightsizerPolicy; }
    public void setWorkloadRightsizerPolicy(WorkloadRightsizerPolicyDto p) { this.workloadRightsizerPolicy = p; }

    public HpaManagerPolicyDto getHpaManagerPolicy() { return hpaManagerPolicy; }
    public void setHpaManagerPolicy(HpaManagerPolicyDto hpaManagerPolicy) { this.hpaManagerPolicy = hpaManagerPolicy; }

    public VpaManagerPolicyDto getVpaManagerPolicy() { return vpaManagerPolicy; }
    public void setVpaManagerPolicy(VpaManagerPolicyDto vpaManagerPolicy) { this.vpaManagerPolicy = vpaManagerPolicy; }

    public NodeOptimizerPolicyDto getNodeOptimizerPolicy() { return nodeOptimizerPolicy; }
    public void setNodeOptimizerPolicy(NodeOptimizerPolicyDto p) { this.nodeOptimizerPolicy = p; }

    public List<NodeTemplateDto> getNodeTemplates() { return nodeTemplates; }
    public void setNodeTemplates(List<NodeTemplateDto> nodeTemplates) { this.nodeTemplates = nodeTemplates; }

    public RebalancerConfigDto getRebalancer() { return rebalancer; }
    public void setRebalancer(RebalancerConfigDto rebalancer) { this.rebalancer = rebalancer; }

    public SafetyGuardsDto getSafetyGuards() { return safetyGuards; }
    public void setSafetyGuards(SafetyGuardsDto safetyGuards) { this.safetyGuards = safetyGuards; }
}
