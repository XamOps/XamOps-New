package com.xammer.cloud.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "capacity_ownership")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapacityOwnership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String cidrId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Instant purchasedAt;

    public CapacityOwnership(String cidrId, Long userId) {
        this.cidrId = cidrId;
        this.userId = userId;
        this.purchasedAt = Instant.now();
    }
}
