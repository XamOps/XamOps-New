package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Access Graph tab, already grouped (design 5.4 item 10, rules in 5.6.8).
 *
 * @param groups in display order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreAccessGraph(String rootName, String rootType, int principalCount, List<Group> groups) {

    /**
     * @param id            e.g. {@code READ,WRITE|ROLE}, or {@code ACTION_ONLY|ROLE}.
     * @param principalType ROLE, USER or GROUP.
     * @param principals    sorted case-insensitively; a blank name is listed as "-".
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Group(String id, List<String> permissions, String principalType, boolean actionOnly, int count,
                        List<String> principals) {
    }
}
