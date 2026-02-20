package com.xammer.billops.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xammer.cloud.domain.User;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import javax.persistence.*;

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

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 20)
    private String gstin;

    @Column(length = 50)
    private String stateName;

    @Column(length = 10)
    private String stateCode;

    @Column(length = 20)
    private String pan;

    @Column(length = 25)
    private String cin;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<User> users;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<CloudAccount> cloudAccounts;

    public Client() {
    }

    public Client(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }
    public String getStateName() { return stateName; }
    public void setStateName(String stateName) { this.stateName = stateName; }
    public String getStateCode() { return stateCode; }
    public void setStateCode(String stateCode) { this.stateCode = stateCode; }
    public String getPan() { return pan; }
    public void setPan(String pan) { this.pan = pan; }
    public String getCin() { return cin; }
    public void setCin(String cin) { this.cin = cin; }
    public List<User> getUsers() { return users; }
    public void setUsers(List<User> users) { this.users = users; }
    public List<CloudAccount> getCloudAccounts() { return cloudAccounts; }
    public void setCloudAccounts(List<CloudAccount> cloudAccounts) { this.cloudAccounts = cloudAccounts; }
}