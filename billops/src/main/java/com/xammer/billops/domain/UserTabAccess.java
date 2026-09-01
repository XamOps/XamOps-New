package com.xammer.billops.domain;

import com.xammer.cloud.domain.AppUser;

import jakarta.persistence.*;

@Entity
@Table(name = "user_tab_access")
public class UserTabAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "tab_name", nullable = false)
    private String tabName;

    public UserTabAccess() {}

    public UserTabAccess(AppUser user, String tabName) {
        this.user = user;
        this.tabName = tabName;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }
    public String getTabName() { return tabName; }
    public void setTabName(String tabName) { this.tabName = tabName; }
}
