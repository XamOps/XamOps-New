package com.xammer.cloud.dto.autospotting.v2;
import java.util.List;
/**
 * What the account's stack can do for AutoSpotting, and what "Update stack" would add
 * (design-aws.md §10.1, §10.3). {@code applicable} is false for a read-only connection, which has no
 * stack to update for this purpose; reconnecting read-write is the fix there.
 */
public record StackCapabilityDto(boolean applicable, String templateVersion, String latestVersion,
                                 boolean updateRequired, List<Missing> missing) {
    /** One capability the conversion needs and the stack lacks. */
    public record Missing(String code, String label) {
    }
}
