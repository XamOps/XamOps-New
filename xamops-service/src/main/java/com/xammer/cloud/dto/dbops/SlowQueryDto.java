package com.xammer.cloud.dto.dbops;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SlowQueryDto {
    private String instanceId;
    private String region;
    private double queryTimeSeconds;
    private double lockTimeSeconds;
    private int rowsExamined;
    private int rowsSent;
    private String query;
    private String queryTimestamp;
}
