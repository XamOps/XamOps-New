package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_posture_finding")
public class UpwindPostureFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upwind_id", nullable = false, unique = true)
    private String upwindId;

    private String severity;

    /** FAIL / PASS / RESOLVED */
    private String status;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(name = "framework_title")
    private String frameworkTitle;

    @Column(name = "check_title", columnDefinition = "TEXT")
    private String checkTitle;

    @Column(name = "cloud_provider")
    private String cloudProvider;

    @Column(name = "cloud_account_id")
    private String cloudAccountId;

    private String region;

    @Column(name = "resource_id", length = 512)
    private String resourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "resource_type")
    private String resourceType;

    /**
     * Kubernetes context, when the finding's resource is a workload rather than a cloud
     * resource. Both come from the v1 payload's {@code resource.cluster_id} /
     * {@code resource.namespace} — we simply never mapped them, which is why K8s findings
     * showed an opaque composite resource id and no cluster or namespace.
     */
    @Column(name = "cluster_id")
    private String clusterId;

    @Column(name = "namespace")
    private String namespace;

    @Column(name = "first_seen_time")
    private Instant firstSeenTime;

    @Column(name = "last_seen_time")
    private Instant lastSeenTime;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
