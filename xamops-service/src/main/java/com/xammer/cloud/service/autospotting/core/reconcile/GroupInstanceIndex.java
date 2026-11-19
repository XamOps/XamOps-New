package com.xammer.cloud.service.autospotting.core.reconcile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps {@code spot_group_instance} (instance id to group) in step with each reconciler sweep
 * (design-aws.md §9.7, plan-phase-22.md task 22.3). The AWS status reader lists the group's instance ids in
 * its health JSON ({@code instanceIds}); this writes them and removes the ones that left the group, so the
 * event listener resolves an EC2 event's group without an AWS call. Data only: it never touches the cloud and
 * never writes {@code spot_group} (the runtime write belongs to {@link ReconcilerStore}).
 */
@Component
public class GroupInstanceIndex {

    private static final Logger log = LoggerFactory.getLogger(GroupInstanceIndex.class);
    private static final int MAX_INSTANCES = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public GroupInstanceIndex(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** The instance ids a group's health JSON lists; empty when absent or unreadable. */
    static List<String> idsOf(ObjectMapper mapper, String healthJson) {
        List<String> ids = new ArrayList<>();
        if (healthJson == null || healthJson.isBlank()) {
            return ids;
        }
        try {
            JsonNode node = mapper.readTree(healthJson).path("instanceIds");
            if (node.isArray()) {
                for (JsonNode n : node) {
                    String id = n.asText("");
                    if (id.startsWith("i-") && id.length() <= 32 && ids.size() < MAX_INSTANCES) {
                        ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("group instance index: unreadable health JSON ({})", e.toString());
        }
        return ids;
    }

    /** Record the instances of {@code group} from {@code healthJson} and drop the ones that left it. Never throws. */
    public void sync(SpotGroup group, String healthJson, Instant now) {
        try {
            JsonNode root = healthJson == null || healthJson.isBlank() ? null : mapper.readTree(healthJson);
            if (root == null || !root.has("instanceIds")) {
                return; // a status without the list says nothing about membership; keep what we have
            }
            List<String> ids = idsOf(mapper, healthJson);
            String key = group.getGroupKey();
            int slash = key == null ? -1 : key.indexOf('/');
            if (slash <= 0) {
                return;
            }
            String region = key.substring(0, slash);
            UUID groupId = group.getId();
            for (String id : ids) {
                jdbc.update("INSERT INTO spot_group_instance (instance_id, group_id, region, lifecycle, seen_at)"
                                + " VALUES (?, ?, ?, NULL, ?) ON CONFLICT (instance_id) DO UPDATE SET"
                                + " group_id = EXCLUDED.group_id, region = EXCLUDED.region, seen_at = EXCLUDED.seen_at",
                        id, groupId, region, Timestamp.from(now));
            }
            // Instances of this group not in the live list have left it (terminated or replaced).
            jdbc.update("DELETE FROM spot_group_instance WHERE group_id = ? AND seen_at < ?", groupId,
                    Timestamp.from(now));
        } catch (Exception e) {
            log.warn("group instance index for {} not updated: {}", group.getId(), e.toString());
        }
    }
}
