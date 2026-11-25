package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What AWS itself knows about one EC2 instance, read with free EC2/Auto Scaling APIs under the connection role
 * (no agent, no CloudWatch cost). {@code findings} turns the raw facts into plain-language observations
 * ("status check failing", "scheduled reboot on ...", "IMDSv1 allowed", "gp2 volume: gp3 is cheaper and faster").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpEc2Facts(
        String instanceId, String region, String name, String state,
        String instanceType, Integer vcpus, Long memoryMiB, String architecture, String platform,
        boolean burstable, String cpuCredits, String networkPerformance, Integer ebsBaselineIops, Double ebsBaselineMbps,
        String availabilityZone, String vpcId, String subnetId, String privateIp, String publicIp,
        String amiId, String amiName, Instant amiCreatedAt, Instant launchTime,
        String lifecycle, String autoScalingGroup, String autoScalingLifecycleState,
        String iamInstanceProfile, List<String> securityGroups, boolean imdsV2Required, boolean ebsOptimized,
        boolean detailedMonitoring, Map<String, String> tags,
        List<Volume> volumes, StatusChecks status, List<ScheduledEvent> scheduledEvents,
        List<Finding> findings, Instant fetchedAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Volume(String volumeId, String device, String type, int sizeGiB, Integer iops, Integer throughputMiBps,
                         boolean encrypted, boolean deleteOnTermination) {}

    /** {@code ok | impaired | insufficient-data | initializing | not-applicable}, as EC2 reports them. */
    public record StatusChecks(String system, String instance) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScheduledEvent(String code, String description, Instant notBefore, Instant notAfter) {}

    /** {@code severity}: CRITICAL, WARNING or INFO. */
    public record Finding(String severity, String title, String detail) {}
}
