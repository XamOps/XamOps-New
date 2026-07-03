package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Feedback submitted by a (typically trial) user, e.g. from the last-day email
 * link or the trial-expired screen.
 */
@Entity
@Table(name = "trial_feedback")
@Data
@NoArgsConstructor
public class TrialFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = true)
    private Long clientId;

    @Column(name = "user_id", nullable = true)
    private Long userId;

    @Column(nullable = true)
    private String email;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(nullable = true)
    private Integer rating;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
