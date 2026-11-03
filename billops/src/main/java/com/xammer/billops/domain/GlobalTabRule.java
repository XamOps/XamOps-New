package com.xammer.billops.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A platform-wide visibility rule for one XamOps sidebar key, set from the BillOps admin User Manager.
 *
 * <p>{@code tabKey} is either a module key ({@code observability}) or a tab key ({@code obs-logs})
 * from the frontend catalog {@code constants/xamopsTabs.ts}. A tab rule wins over its module's rule.
 * <ul>
 *   <li>{@link Mode#HIDE}: hidden from every ROLE_XAMOPS user, in the sidebar and by URL.</li>
 *   <li>{@link Mode#GRANT}: shown to every ROLE_XAMOPS user, including users whose
 *       {@code user_tab_access} list would leave it out.</li>
 * </ul>
 * Users listed in {@link #getExceptions()} are not affected by the rule; their own per-user access
 * decides. Table created by Flyway migration {@code V20261102_1__global_tab_rules.sql} in
 * xamops-service (or by Hibernate locally).
 */
@Entity
@Table(name = "global_tab_rule",
        uniqueConstraints = @UniqueConstraint(name = "uk_global_tab_rule_tab_key", columnNames = "tab_key"))
public class GlobalTabRule {

    public enum Mode { HIDE, GRANT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tab_key", nullable = false, length = 100)
    private String tabKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 10)
    private Mode mode;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GlobalTabRuleException> exceptions = new ArrayList<>();

    public GlobalTabRule() {}

    public GlobalTabRule(String tabKey, Mode mode) {
        this.tabKey = tabKey;
        this.mode = mode;
    }

    public Long getId() { return id; }
    public String getTabKey() { return tabKey; }
    public void setTabKey(String tabKey) { this.tabKey = tabKey; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<GlobalTabRuleException> getExceptions() { return exceptions; }
}
