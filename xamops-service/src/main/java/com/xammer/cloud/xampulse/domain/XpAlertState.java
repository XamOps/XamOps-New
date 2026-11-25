package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Maps xp_alert_state: edge-triggered state per (rule, group). Joins to its tenant through the rule. */
@Entity
@Table(name = "xp_alert_state")
@IdClass(XpAlertStateId.class)
@Getter
@Setter
@NoArgsConstructor
public class XpAlertState {

    @Id
    @Column(name = "rule_id")
    private Long ruleId;

    @Id
    @Column(name = "group_key")
    private String groupKey;

    @Column(name = "state")
    private String state;

    @Column(name = "since")
    private Instant since;

    @Column(name = "last_eval_at")
    private Instant lastEvalAt;

    @Column(name = "last_value")
    private Double lastValue;
}
