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

    /**
     * When set, this client is "owned" by the ROLE_BILLOPS_ADMIN_SUB user with this ID.
     * NULL = belongs to the primary ROLE_BILLOPS_ADMIN pool (visible to all primary admins).
     * Only the owning sub-admin (and primary admins) can see/manage this client.
     */
    @Column(name = "owner_admin_id")
    private Long ownerAdminId;

    @Column(name = "email")
    private String email;

    // --- Trial lifecycle (mirrors xamops-service Client; same `client` table) ---
    @Column(name = "plan_type")
    private String planType; // PAID | TRIAL

    @Column(name = "trial_status")
    private String trialStatus; // ACTIVE | EXPIRED | CONVERTED

    @Column(name = "trial_start_at")
    private java.time.LocalDateTime trialStartAt;

    @Column(name = "trial_end_at")
    private java.time.LocalDateTime trialEndAt;

    @Column(name = "reminder5_sent_at")
    private java.time.LocalDateTime reminder5SentAt;

    @Column(name = "final_day_sent_at")
    private java.time.LocalDateTime finalDaySentAt;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<User> users;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<CloudAccount> cloudAccounts;

    // public Client() {
    // }

    public Client(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getGstin() {
        return gstin;
    }

    public void setGstin(String gstin) {
        this.gstin = gstin;
    }

    public String getStateName() {
        return stateName;
    }

    public void setStateName(String stateName) {
        this.stateName = stateName;
    }

    public String getStateCode() {
        return stateCode;
    }

    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    public String getPan() {
        return pan;
    }

    public void setPan(String pan) {
        this.pan = pan;
    }

    public String getCin() {
        return cin;
    }

    public void setCin(String cin) {
        this.cin = cin;
    }

    public List<User> getUsers() {
        return users;
    }

    public void setUsers(List<User> users) {
        this.users = users;
    }

    public List<CloudAccount> getCloudAccounts() {
        return cloudAccounts;
    }

    public void setCloudAccounts(List<CloudAccount> cloudAccounts) {
        this.cloudAccounts = cloudAccounts;
    }

    public Long getOwnerAdminId() {
        return ownerAdminId;
    }

    public void setOwnerAdminId(Long ownerAdminId) {
        this.ownerAdminId = ownerAdminId;
    }
}