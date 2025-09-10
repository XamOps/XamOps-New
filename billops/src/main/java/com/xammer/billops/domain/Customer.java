package com.xammer.billops.domain;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*; // Changed from jakarta.persistence
import java.util.List;

@Entity
@Getter
@Setter
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String companyName;

    private String awsRoleArn;

    @OneToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    private User user;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CloudAccount> cloudAccounts;
}