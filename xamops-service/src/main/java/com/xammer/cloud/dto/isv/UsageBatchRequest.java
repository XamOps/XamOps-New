package com.xammer.cloud.dto.isv;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Batch of usage events submitted in a single API call.
 * Max 100 events per batch (enforced by UsageEventService).
 */
@Data
public class UsageBatchRequest {

    private List<UsageEventRequest> events = new ArrayList<>();
}
