package com.xammer.cloud.dto.onboarding;

/**
 * The installer's {@code POST /api/v1/agent/connect/exchange} body (design §4.3). All fields are
 * supplied by the admin's shell; the token (bearer header) is the only authenticated secret, so
 * {@code clusterName}/{@code cloudAccountId} act as wrong-context guards, not authentication.
 */
public class ConnectExchangeRequest {

    private String clusterName;
    private String cloudAccountId;
    private String region;
    private String phase; // READ_ONLY | AUTOMATION
    private String installerVersion;
    private Detected detected;

    public static class Detected {
        private String nodeScaler;
        private String identityMode;
        private String kubeVersion;

        public String getNodeScaler() { return nodeScaler; }
        public void setNodeScaler(String nodeScaler) { this.nodeScaler = nodeScaler; }

        public String getIdentityMode() { return identityMode; }
        public void setIdentityMode(String identityMode) { this.identityMode = identityMode; }

        public String getKubeVersion() { return kubeVersion; }
        public void setKubeVersion(String kubeVersion) { this.kubeVersion = kubeVersion; }
    }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getCloudAccountId() { return cloudAccountId; }
    public void setCloudAccountId(String cloudAccountId) { this.cloudAccountId = cloudAccountId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getInstallerVersion() { return installerVersion; }
    public void setInstallerVersion(String installerVersion) { this.installerVersion = installerVersion; }

    public Detected getDetected() { return detected; }
    public void setDetected(Detected detected) { this.detected = detected; }
}
