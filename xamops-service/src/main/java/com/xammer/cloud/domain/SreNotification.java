package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "sre_notification")
public class SreNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sre_notif_seq_gen")
    @SequenceGenerator(name = "sre_notif_seq_gen", sequenceName = "sre_notification_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "investigation_id", nullable = false)
    private Long investigationId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
