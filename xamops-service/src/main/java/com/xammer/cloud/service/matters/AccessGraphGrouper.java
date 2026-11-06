package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreAccessGraph;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.xammer.cloud.service.matters.DatastoreRowMapper.text;

/**
 * Groups {@code /access_graph/overview} principals into the Access Graph boxes (design 5.6.8).
 *
 * <p>The rule was verified against the console on the S3 shape (36 principals and the exact boxes).
 * {@code list} is ignored; a principal counts when it has at least one of read, write, delete and
 * management, or a non-empty {@code action[]}. Pure and dependency-free.
 */
@Component
public class AccessGraphGrouper {

    /** Permission flags in their fixed display order. {@code list} is deliberately absent. */
    private static final List<String> FLAGS = List.of("read", "write", "delete", "management");

    /** Child key → principal type, in the tie-break order ROLE, USER, GROUP. */
    private static final Map<String, String> CHILD_TYPES = orderedChildTypes();

    private static final String ACTION_ONLY = "ACTION_ONLY";
    private static final String BLANK_NAME = "-";

    private static Map<String, String> orderedChildTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("roles", "ROLE");
        types.put("users", "USER");
        types.put("groups", "GROUP");
        return types;
    }

    private static final List<String> TYPE_ORDER = List.copyOf(CHILD_TYPES.values());

    private record Key(List<String> permissions, String type) {
    }

    public DatastoreAccessGraph group(JsonNode body) {
        JsonNode graph = body.path("access_graph");
        JsonNode children = graph.path("children");

        // Steps 1 to 4: read roles, users and groups; keep principals with permissions or actions.
        Map<Key, List<String>> members = new LinkedHashMap<>();
        int principalCount = 0;
        for (Map.Entry<String, String> child : CHILD_TYPES.entrySet()) {
            for (JsonNode principal : children.path(child.getKey())) {
                List<String> permissions = new ArrayList<>();
                for (String flag : FLAGS) {
                    JsonNode value = principal.path(flag);
                    if (value.isBoolean() && value.booleanValue()) {
                        permissions.add(flag.toUpperCase(Locale.ROOT));
                    }
                }
                if (permissions.isEmpty() && !hasAction(principal.path("action"))) {
                    continue;
                }
                principalCount++;
                // A principal with actions but no permission goes to the group with an empty permission
                // list (actionOnly).
                // TODO(G2): placement of action-only principals is unseen; see datastores-page-mapping.md, third batch
                String name = text(principal, "name");
                members.computeIfAbsent(new Key(List.copyOf(permissions), child.getValue()), k -> new ArrayList<>())
                        .add(name == null || name.isBlank() ? BLANK_NAME : name);
            }
        }

        // Step 5: one group per (permission list, type), principals sorted case-insensitively.
        Map<List<String>, Integer> setTotals = new LinkedHashMap<>();
        members.forEach((key, names) -> setTotals.merge(key.permissions(), names.size(), Integer::sum));
        List<DatastoreAccessGraph.Group> groups = new ArrayList<>();
        members.forEach((key, names) -> {
            List<String> sorted = new ArrayList<>(names);
            sorted.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
            boolean actionOnly = key.permissions().isEmpty();
            String id = (actionOnly ? ACTION_ONLY : String.join(",", key.permissions())) + "|" + key.type();
            groups.add(new DatastoreAccessGraph.Group(id, key.permissions(), key.type(), actionOnly, sorted.size(),
                    sorted));
        });

        // Step 6 (A22): sets by total principals descending, then more permissions, then flag sequence;
        // action-only sets last; within a set, types by count descending, ties ROLE, USER, GROUP.
        Comparator<DatastoreAccessGraph.Group> order = Comparator
                .comparing((DatastoreAccessGraph.Group g) -> g.actionOnly())
                .thenComparing(g -> setTotals.get(g.permissions()), Comparator.reverseOrder())
                .thenComparing(g -> g.permissions().size(), Comparator.reverseOrder())
                .thenComparing(DatastoreAccessGraph.Group::permissions, AccessGraphGrouper::compareFlagSequence)
                .thenComparing(DatastoreAccessGraph.Group::count, Comparator.reverseOrder())
                .thenComparing(g -> TYPE_ORDER.indexOf(g.principalType()));
        groups.sort(order);

        return new DatastoreAccessGraph(text(graph, "name"), text(graph, "type"), principalCount, groups);
    }

    private static boolean hasAction(JsonNode actions) {
        for (JsonNode action : actions) {
            if (action.isTextual() && !action.asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** Lexicographic by flag index: READ < WRITE < DELETE < MANAGEMENT. */
    private static int compareFlagSequence(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int diff = Integer.compare(flagIndex(a.get(i)), flagIndex(b.get(i)));
            if (diff != 0) {
                return diff;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int flagIndex(String permission) {
        return FLAGS.indexOf(permission.toLowerCase(Locale.ROOT));
    }
}
