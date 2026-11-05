package com.xammer.cloud.xampulse.domain;

import java.io.Serializable;
import java.util.Objects;

/** Composite key (rule_id, group_key) of xp_alert_state. */
public class XpAlertStateId implements Serializable {
    private Long ruleId;
    private String groupKey;

    public XpAlertStateId() {}
    public XpAlertStateId(Long ruleId, String groupKey) { this.ruleId = ruleId; this.groupKey = groupKey; }
    public Long getRuleId() { return ruleId; }
    public String getGroupKey() { return groupKey; }

    @Override public boolean equals(Object o) {
        return o instanceof XpAlertStateId k && Objects.equals(ruleId, k.ruleId) && Objects.equals(groupKey, k.groupKey);
    }
    @Override public int hashCode() { return Objects.hash(ruleId, groupKey); }
}
