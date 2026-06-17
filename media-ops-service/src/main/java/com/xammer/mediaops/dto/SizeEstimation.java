package com.xammer.mediaops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SizeEstimation {
    private long lowerBoundBytes;
    private long estimatedBytes;
    private long upperBoundBytes;
}
