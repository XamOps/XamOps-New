package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String name;

    @Column(nullable = true)
    private String email;

    // Establishes a one-to-many relationship with users
    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @lombok.ToString.Exclude
    private List<User> users;

    // Establishes a one-to-many relationship with cloud accounts
    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @lombok.ToString.Exclude
    private List<CloudAccount> cloudAccounts;

    public Client(String name) {
        this.name = name;
    }
}
