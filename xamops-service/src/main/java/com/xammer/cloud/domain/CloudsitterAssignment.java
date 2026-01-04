package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;

@Entity
@Data
@NoArgsConstructor
@Table(name = "cloudsitter_assignments")
public class CloudsitterAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String resourceId; // AWS Instance ID (e.g., i-1234567890abcdef0)
    private String accountId; // AWS Account ID
    private String region; // AWS Region (e.g., us-east-1)

    @ManyToOne
    @JoinColumn(name = "policy_id")
    private CloudsitterPolicy policy;

    private boolean active; // Is this assignment currently enabled?
}