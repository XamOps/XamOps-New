package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

@Entity
@Data
@NoArgsConstructor
@Table(name = "azure_cloudsitter_assignments")
public class AzureCloudsitterAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String vmName;           // Azure VM name
    private String resourceGroup;    // Azure resource group
    private String subscriptionId;   // Azure subscription ID

    @ManyToOne
    @JoinColumn(name = "policy_id")
    private CloudsitterPolicy policy;

    private boolean active;
}
