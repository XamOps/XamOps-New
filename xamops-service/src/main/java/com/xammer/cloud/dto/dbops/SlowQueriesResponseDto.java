package com.xammer.cloud.dto.dbops;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SlowQueriesResponseDto {
    private boolean logsEnabled;
    private String message;
    private List<SlowQueryDto> queries;
}
