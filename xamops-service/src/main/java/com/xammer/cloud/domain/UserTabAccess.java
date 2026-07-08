package com.xammer.cloud.domain;

import javax.persistence.*;

/**
 * Per-user sidebar tab/module access for XamOps users.
 *
 * Maps the shared {@code user_tab_access} table (the same table BillOps uses for its
 * ROLE_BILLOPS_ADMIN sub-users). Each row grants the referenced user access to a single
 * sidebar tab, identified by {@code tabName} (see the frontend XAMOPS_MODULES catalog).
 *
 * Semantics: if a user has NO rows here, no restriction is applied (full sidebar).
 * If a user has one or more rows, the sidebar is limited to exactly those tab keys.
 */
@Entity
@Table(name = "user_tab_access")
public class UserTabAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "tab_name", nullable = false)
    private String tabName;

    public UserTabAccess() {}

    public UserTabAccess(User user, String tabName) {
        this.user = user;
        this.tabName = tabName;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getTabName() { return tabName; }
    public void setTabName(String tabName) { this.tabName = tabName; }
}
