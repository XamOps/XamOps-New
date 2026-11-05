package com.xammer.cloud.dto.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The {@code POST /api/v1/agent/connect/exchange} success body (design §4.3). The agent key is
 * returned exactly once, here; {@link #toString()} redacts it so it can never reach a log.
 *
 * <p>{@code agentEnv}, {@code images} and {@code iam} are populated by the full session wiring
 * (feature catalog §5.5, permission manifest §5.4) and may be null on the focused exchange path.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConnectExchangeResponse {

    private String agentKey;
    private String keyKind; // DATA | AUTOMATION
    private String serverUrl;
    private String clusterName;
    private String cloudAccountId;
    private List<String> features;
    private Map<String, String> agentEnv;
    private Map<String, String> images;
    private Iam iam;

    public static class Iam {
        private boolean required;
        private String templateUrl;
        private String templateSha256;
        private String stackName;
        private Map<String, String> parameters;

        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }

        public String getTemplateUrl() { return templateUrl; }
        public void setTemplateUrl(String templateUrl) { this.templateUrl = templateUrl; }

        public String getTemplateSha256() { return templateSha256; }
        public void setTemplateSha256(String templateSha256) { this.templateSha256 = templateSha256; }

        public String getStackName() { return stackName; }
        public void setStackName(String stackName) { this.stackName = stackName; }

        public Map<String, String> getParameters() { return parameters; }
        public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
    }

    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }

    public String getKeyKind() { return keyKind; }
    public void setKeyKind(String keyKind) { this.keyKind = keyKind; }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getCloudAccountId() { return cloudAccountId; }
    public void setCloudAccountId(String cloudAccountId) { this.cloudAccountId = cloudAccountId; }

    public List<String> getFeatures() { return features; }
    public void setFeatures(List<String> features) { this.features = features; }

    public Map<String, String> getAgentEnv() { return agentEnv; }
    public void setAgentEnv(Map<String, String> agentEnv) { this.agentEnv = agentEnv; }

    public Map<String, String> getImages() { return images; }
    public void setImages(Map<String, String> images) { this.images = images; }

    public Iam getIam() { return iam; }
    public void setIam(Iam iam) { this.iam = iam; }

    /** Never leak the key through a log or error string (design §4.3). */
    @Override
    public String toString() {
        return "ConnectExchangeResponse{keyKind=" + keyKind + ", clusterName=" + clusterName
                + ", cloudAccountId=" + cloudAccountId + ", agentKey=***REDACTED***}";
    }
}
