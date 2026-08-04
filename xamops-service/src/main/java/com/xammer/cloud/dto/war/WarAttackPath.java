package com.xammer.cloud.dto.war;

/**
 * The concrete way an attacker or crypto-miner monetises a failed control.
 *
 * <p>
 * Compliance frameworks group findings by control family (IAM, Network, Data).
 * That is the right axis for an auditor and the wrong axis for an incident.
 * WAR groups the same findings by attack path so the answer to "how would
 * someone get into this account and start mining?" is a single row, not a
 * cross-reference exercise across six categories.
 */
public enum WarAttackPath {

    ACCOUNT_TAKEOVER("Account Takeover", "Full control of the account or organization"),
    CREDENTIAL_THEFT("Credential Theft", "Long-lived or exposed keys that bots harvest within minutes"),
    PRIVILEGE_ESCALATION("Privilege Escalation", "One compromised identity able to do far more than intended"),
    PUBLIC_EXPOSURE("Public Exposure", "Resources reachable from the open internet"),
    LATERAL_MOVEMENT("Lateral Movement", "Spread from one compromised workload across the fleet"),
    CRYPTO_MINING("Crypto Mining", "Hijacked compute running mining workloads on your bill"),
    DATA_EXFILTRATION("Data Exfiltration", "Bulk data pulled out, with egress charges on top"),
    RANSOMWARE("Ransomware", "Data encrypted or deleted with no clean recovery path"),
    AI_TOKEN_ABUSE("AI / Token Abuse", "Model endpoints invoked at scale by someone else"),
    DDOS_AMPLIFICATION("DDoS Amplification", "Attack traffic driving Auto Scaling into a billing spike"),
    BLIND_SPOT("Detection Blind Spot", "The attack runs, and nothing tells you until the invoice"),
    COST_ABUSE("Cost Abuse", "Ungoverned provisioning that inflates spend without attribution");

    private final String label;
    private final String description;

    WarAttackPath(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
