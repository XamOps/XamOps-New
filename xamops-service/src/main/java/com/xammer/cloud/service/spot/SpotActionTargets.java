package com.xammer.cloud.service.spot;

import java.util.Arrays;
import java.util.List;

/**
 * Node names of an {@code autoscaling-actions} record's {@code target}: {@code node/<n>} or
 * {@code nodes/<a>,<b>} (matches {@code drain/executor.go} {@code nodeTarget}).
 */
public final class SpotActionTargets {

    private SpotActionTargets() {
    }

    static List<String> targetNodes(String target) {
        if (target == null) return List.of();
        if (target.startsWith("node/"))  return List.of(target.substring(5));
        if (target.startsWith("nodes/")) return Arrays.stream(target.substring(6).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return List.of();
    }
}
