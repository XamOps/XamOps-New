package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;

@Entity
@Data
@NoArgsConstructor
@Table(name = "gcp_cloudsitter_assignments")
public class GcpCloudsitterAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String instanceName; // GCP instance name
    private String zone;         // GCP zone (e.g., us-central1-a)
    private String gcpProjectId; // GCP project ID

    @ManyToOne
    @JoinColumn(name = "policy_id")
    private CloudsitterPolicy policy;

    private boolean active;
}
