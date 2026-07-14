package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_account_connection")
public class UpwindAccountConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Provider account id: AWS account id / GCP project id / Azure subscription id */
    @Column(name = "cloud_account_id", nullable = false, unique = true)
    private String cloudAccountId;

    /** NOT_CONNECTED / PENDING / CONNECTED */
    @Column(nullable = false)
    private String status = "NOT_CONNECTED";

    /** Helm/Bash sensor install command registered by a Xammer admin. */
    @Column(name = "sensor_install_command", columnDefinition = "TEXT")
    private String sensorInstallCommand;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
