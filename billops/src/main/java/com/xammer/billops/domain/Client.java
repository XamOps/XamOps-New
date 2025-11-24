package com.xammer.billops.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xammer.cloud.domain.User;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;

@Entity
@Data
@NoArgsConstructor
@Table(name = "client")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    // --- NEW BILLING FIELDS ---
    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 20)
    private String gstin; // GST Identification Number

    @Column(length = 50)
    private String stateName;

    @Column(length = 10)
    private String stateCode;

    @Column(length = 20)
    private String pan; // Permanent Account Number

    @Column(length = 25)
    private String cin; // Corporate Identity Number
    // --------------------------

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<User> users;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<CloudAccount> cloudAccounts;

    public Client(String name) {
        this.name = name;
    }
}