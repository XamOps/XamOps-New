package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_incident_event. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_incident_event")
@Getter
@Setter
@NoArgsConstructor
public class XpIncidentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id")
    private Long incidentId;

    @Column(name = "ts")
    private Instant ts;

    @Column(name = "kind")
    private String kind;

    @Column(name = "payload_json", columnDefinition = "text")
    private String payloadJson;

}
