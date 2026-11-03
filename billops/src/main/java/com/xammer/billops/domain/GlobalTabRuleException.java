package com.xammer.billops.domain;

import com.xammer.cloud.domain.AppUser;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A user a {@link GlobalTabRule} does not apply to. For this user the page falls back to their own
 * {@code user_tab_access} settings, as if the rule did not exist.
 */
@Entity
@Table(name = "global_tab_rule_exception",
        uniqueConstraints = @UniqueConstraint(name = "uk_global_tab_rule_exception", columnNames = {"rule_id", "user_id"}),
        indexes = @Index(name = "idx_global_tab_rule_exception_user", columnList = "user_id"))
public class GlobalTabRuleException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false, foreignKey = @ForeignKey(name = "fk_gtre_rule"))
    private GlobalTabRule rule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_gtre_user"))
    private AppUser user;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public GlobalTabRuleException() {}

    public GlobalTabRuleException(GlobalTabRule rule, AppUser user) {
        this.rule = rule;
        this.user = user;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public GlobalTabRule getRule() { return rule; }
    public AppUser getUser() { return user; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
