// File: KarpenterConfigDto.java
// Location: src/main/java/com/xammer/cloud/dto/karpenter/KarpenterConfigDto.java
// 
// ⚠️ CREATE NEW FOLDER: src/main/java/com/xammer/cloud/dto/karpenter/
// ⚠️ This is a NEW file, not a modification

package com.xammer.cloud.dto.karpenter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * KarpenterConfigDto: Request DTO for configuring Karpenter NodePool.
 * 
 * This DTO captures the user's choices from the "Spot Saver" form:
 * - Should we use Spot instances?
 * - What instance families?
 * - How long should nodes live?
 * - Should consolidation be enabled?
 * 
 * Flow:
 * 1. User fills form in eks-details.html
 * 2. Frontend sends POST to /api/karpenter/configure with this DTO
 * 3. KarpenterService receives this DTO
 * 4. Creates NodePool and EC2NodeClass based on these values
 * 5. Applies to cluster
 * 
 * Example JSON from frontend:
 * {
 * "accountId": "123456789012",
 * "clusterId": "prod-cluster-1",
 * "region": "us-east-1",
 * "useSpot": true,
 * "instanceFamilies": ["c5", "m5", "r5"],
 * "ttlSeconds": 604800,
 * "consolidationEnabled": true
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KarpenterConfigDto {

    /**
     * Customer's AWS Account ID (12 digits).
     * Used to load the correct CloudAccount and get credentials.
     */
    private String accountId;

    /**
     * EKS cluster name.
     * Example: "prod-cluster-1"
     */
    private String clusterId;

    /**
     * AWS region where cluster runs.
     * Example: "us-east-1"
     */
    private String region;

    /**
     * Should Karpenter use Spot instances?
     * - true = Spot instances (cheaper, can be interrupted)
     * - false = On-Demand only (more expensive, stable)
     * 
     * Default: true (for cost optimization)
     */
    private Boolean useSpot;

    /**
     * List of EC2 instance families Karpenter can launch.
     * Example: ["c5", "m5", "r5", "t3"]
     * 
     * ⚠️ POC CONSTRAINT:
     * Backend will override this to ["t3"] only.
     * User's selection here will be IGNORED.
     * This prevents accidental cost spikes during POC phase.
     */
    private List<String> instanceFamilies;

    /**
     * Time in seconds before terminating an empty node.
     * Example: 30 = 30 seconds
     * Meaning: If all pods drain from a node, Karpenter waits 30s then kills the
     * node.
     * 
     * Default: 30 seconds
     */
    private Integer ttlSecondsAfterEmpty;

    /**
     * Maximum time in seconds a node can live before forced termination.
     * Example: 604800 = 7 days
     * Meaning: Nodes are replaced every 7 days (for security patching, AMI updates)
     * 
     * Default: 2592000 (30 days)
     */
    private Integer ttlSecondsUntilExpired;

    /**
     * Should Karpenter consolidate nodes?
     * - true = Bin-pack pods to fewer nodes when possible (saves cost)
     * - false = Don't consolidate (nodes stay as they are)
     * 
     * Default: true
     */
    private Boolean consolidationEnabled;

    /**
     * NodePool name (optional, user-provided).
     * If not provided, defaults to "spot-saver"
     * Example: "spot-saver", "cost-optimizer"
     */
    private String nodePoolName;

    /**
     * EC2NodeClass name (optional, user-provided).
     * If not provided, defaults to "default"
     * Example: "default", "custom-config"
     */
    private String ec2NodeClassName;
}