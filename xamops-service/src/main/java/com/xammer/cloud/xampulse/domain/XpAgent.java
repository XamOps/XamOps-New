package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_agent. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_agent")
@Getter
@Setter
@NoArgsConstructor
public class XpAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "instance_id")
    private String instanceId;

    @Column(name = "region")
    private String region;

    @Column(name = "state")
    private String state;

    @Column(name = "agent_version")
    private String agentVersion;

    @Column(name = "collector_version")
    private String collectorVersion;

    @Column(name = "os")
    private String os;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "config_version")
    private long configVersion;

    @Column(name = "collection_level")
    private String collectionLevel;

    @Column(name = "pending_command")
    private String pendingCommand;

    /** Last heartbeat health report (sanitised, bounded JSON: sourcesStatus, errors, queue). */
    @Column(name = "health_json", columnDefinition = "TEXT")
    private String healthJson;

}
