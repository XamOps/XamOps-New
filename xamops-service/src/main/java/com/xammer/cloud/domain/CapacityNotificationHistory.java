package com.xammer.cloud.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "capacity_notification_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapacityNotificationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String cidrId; // Capacity/Policy ID

    @Column(nullable = false)
    private Integer thresholdPercent; // 80 or 90

    @Column(nullable = false)
    private Double actualPercent; // Actual consumption percentage when notified

    @Column(nullable = false)
    private Boolean wasExtended; // Whether the capacity was extended

    @Column(nullable = false)
    private Instant notifiedAt;

    @Column(length = 500)
    private String capacityName; // Store the policy name for reference

    @Column(length = 100)
    private String userEmail; // Store email address it was sent to

    public CapacityNotificationHistory(Long userId, String cidrId, Integer thresholdPercent,
                                       Double actualPercent, Boolean wasExtended,
                                       String capacityName, String userEmail) {
        this.userId = userId;
        this.cidrId = cidrId;
        this.thresholdPercent = thresholdPercent;
        this.actualPercent = actualPercent;
        this.wasExtended = wasExtended;
        this.capacityName = capacityName;
        this.userEmail = userEmail;
        this.notifiedAt = Instant.now();
    }
}
