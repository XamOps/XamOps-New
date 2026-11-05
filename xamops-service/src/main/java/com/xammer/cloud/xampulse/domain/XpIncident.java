package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_incident. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_incident")
@Getter
@Setter
@NoArgsConstructor
public class XpIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "title")
    private String title;

    @Column(name = "severity")
    private String severity;

    @Column(name = "state")
    private String state;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "ack_by")
    private String ackBy;

    @Column(name = "snoozed_until")
    private Instant snoozedUntil;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "summary_json", columnDefinition = "text")
    private String summaryJson;

}
