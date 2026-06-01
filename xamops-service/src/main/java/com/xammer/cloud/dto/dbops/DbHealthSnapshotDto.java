package com.xammer.cloud.dto.dbops;

import com.xammer.cloud.domain.DbHealthSnapshot;
import java.util.List;

public class DbHealthSnapshotDto {
    private String instanceId;
    private int score;
    private String status;
    private List<String> issues;
    private String computedAt;

    public static DbHealthSnapshotDto from(DbHealthSnapshot s, List<String> issues) {
        DbHealthSnapshotDto d = new DbHealthSnapshotDto();
        d.instanceId  = s.getInstanceId();
        d.score       = s.getScore();
        d.status      = s.getStatus();
        d.issues      = issues;
        d.computedAt  = s.getComputedAt().toString();
        return d;
    }

    public String getInstanceId() { return instanceId; }
    public int getScore()         { return score; }
    public String getStatus()     { return status; }
    public List<String> getIssues(){ return issues; }
    public String getComputedAt() { return computedAt; }
}
