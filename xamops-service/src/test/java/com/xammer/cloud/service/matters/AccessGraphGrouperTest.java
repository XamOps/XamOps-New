package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreAccessGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 5.6.8. The inputs come from a principal generator with invented names, shaped like the S3,
 * Azure and Postgres access graphs (counts only; no fixture is read).
 */
class AccessGraphGrouperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AccessGraphGrouper grouper = new AccessGraphGrouper();

    /** Builds an {@code access_graph} body principal by principal. */
    private final class Graph {
        private final ObjectNode body = json.createObjectNode();
        private final ObjectNode children;
        private int seq;

        Graph(String type, String name) {
            ObjectNode graph = body.putObject("access_graph");
            graph.put("type", type);
            graph.put("name", name);
            children = graph.putObject("children");
            children.putArray("assume_role").addObject().put("role_name", "ignored-assume").putArray("principals");
        }

        /**
         * Adds {@code n} principals under {@code childKey}.
         *
         * @param flags   the flags set to true; every other flag is false.
         * @param actions values of {@code action[]}, or null to leave the key out.
         * @param azure   add {@code type} and {@code delete}, as the Azure shape has.
         */
        Graph add(String childKey, int n, List<String> flags, List<String> actions, boolean azure) {
            ArrayNode array = children.has(childKey) ? (ArrayNode) children.get(childKey) : children.putArray(childKey);
            for (int i = 0; i < n; i++) {
                ObjectNode p = array.addObject();
                p.put("name", "principal-" + childKey + "-" + (seq++));
                for (String flag : azure ? List.of("read", "write", "delete", "list", "management")
                        : List.of("read", "write", "list", "management")) {
                    p.put(flag, flags.contains(flag));
                }
                if (azure) {
                    p.put("type", childKey.substring(0, childKey.length() - 1));
                }
                if (actions != null) {
                    ArrayNode a = p.putArray("action");
                    actions.forEach(a::add);
                }
            }
            return this;
        }

        JsonNode build() {
            return body;
        }
    }

    private static List<String> summary(DatastoreAccessGraph graph) {
        return graph.groups().stream()
                .map(g -> g.id() + "=" + g.count())
                .toList();
    }

    @Test
    @DisplayName("S3 shape: 36 principals with the exact console boxes and order")
    void s3Shape() {
        List<String> none = List.of();
        JsonNode body = new Graph("s3", "synthetic-bucket")
                .add("roles", 19, List.of("read"), null, false)
                .add("roles", 9, List.of("read", "write", "management"), null, false)
                .add("roles", 1, List.of("read", "write"), null, false)
                .add("roles", 100, List.of("list"), null, false)
                .add("roles", 75, none, null, false)
                .add("users", 1, List.of("read", "list"), null, false)
                .add("users", 5, List.of("read", "write", "management"), null, false)
                .add("users", 6, List.of("list"), null, false)
                .add("groups", 1, List.of("read", "write", "list", "management"), null, false)
                .add("groups", 1, none, null, false)
                .build();
        assertThat(body.path("access_graph").path("children").path("roles").size()).isEqualTo(204);
        assertThat(body.path("access_graph").path("children").path("users").size()).isEqualTo(12);
        assertThat(body.path("access_graph").path("children").path("groups").size()).isEqualTo(2);

        DatastoreAccessGraph graph = grouper.group(body);
        assertThat(graph.principalCount()).isEqualTo(36);
        assertThat(graph.rootName()).isEqualTo("synthetic-bucket");
        assertThat(graph.rootType()).isEqualTo("s3");
        assertThat(summary(graph)).containsExactly(
                "READ|ROLE=19", "READ|USER=1",
                "READ,WRITE,MANAGEMENT|ROLE=9", "READ,WRITE,MANAGEMENT|USER=5", "READ,WRITE,MANAGEMENT|GROUP=1",
                "READ,WRITE|ROLE=1");
        DatastoreAccessGraph.Group rwm = graph.groups().get(2);
        assertThat(rwm.permissions()).containsExactly("READ", "WRITE", "MANAGEMENT");
        assertThat(rwm.principalType()).isEqualTo("ROLE");
        assertThat(rwm.actionOnly()).isFalse();
        assertThat(rwm.principals()).hasSize(9);
    }

    @Test
    @DisplayName("Azure shape: 88 principals, action-only roles (7) last")
    void azureShape() {
        List<String> acts = List.of("join", "joinviaserviceendpoint");
        JsonNode body = new Graph("azure_blob", "synthetic-container")
                .add("roles", 28, List.of("read", "write", "delete", "management"), acts, true)
                .add("roles", 19, List.of("read", "write", "delete"), List.of(), true)
                .add("roles", 15, List.of("read", "list"), List.of(), true)
                .add("roles", 9, List.of("read", "write"), acts, true)
                .add("roles", 7, List.of("read", "write", "management"), List.of(), true)
                .add("roles", 1, List.of("read", "management"), List.of(), true)
                .add("roles", 7, List.of("list"), acts, true)
                .add("users", 2, List.of("read", "write", "delete", "list", "management"), List.of(), true)
                .build();
        assertThat(body.path("access_graph").path("children").path("roles").size()).isEqualTo(86);

        DatastoreAccessGraph graph = grouper.group(body);
        assertThat(graph.principalCount()).isEqualTo(88);
        assertThat(summary(graph)).containsExactly(
                "READ,WRITE,DELETE,MANAGEMENT|ROLE=28", "READ,WRITE,DELETE,MANAGEMENT|USER=2",
                "READ,WRITE,DELETE|ROLE=19",
                "READ|ROLE=15",
                "READ,WRITE|ROLE=9",
                "READ,WRITE,MANAGEMENT|ROLE=7",
                "READ,MANAGEMENT|ROLE=1",
                "ACTION_ONLY|ROLE=7");
        DatastoreAccessGraph.Group last = graph.groups().get(graph.groups().size() - 1);
        assertThat(last.actionOnly()).isTrue();
        assertThat(last.permissions()).isEmpty();
        assertThat(last.principalType()).isEqualTo("ROLE");
    }

    @Test
    @DisplayName("Postgres shape: 4 principals; equal totals put more permissions first")
    void postgresShape() {
        JsonNode body = new Graph("postgres", "synthetic-db")
                .add("users", 1, List.of("read", "write", "delete"), null, true)
                .add("users", 2, List.of("read"), null, true)
                .add("users", 1, List.of("read", "write", "delete", "management"), null, true)
                .build();
        DatastoreAccessGraph graph = grouper.group(body);
        assertThat(graph.principalCount()).isEqualTo(4);
        assertThat(summary(graph)).containsExactly(
                "READ|USER=2", "READ,WRITE,DELETE,MANAGEMENT|USER=1", "READ,WRITE,DELETE|USER=1");
    }

    @Test
    @DisplayName("equal totals and equal permission counts order by flag sequence")
    void flagSequenceTieBreak() {
        JsonNode body = new Graph("s3", "b")
                .add("roles", 1, List.of("write", "management"), null, true)
                .add("roles", 1, List.of("read", "delete"), null, true)
                .add("roles", 1, List.of("read", "write"), null, true)
                .build();
        assertThat(summary(grouper.group(body)))
                .containsExactly("READ,WRITE|ROLE=1", "READ,DELETE|ROLE=1", "WRITE,MANAGEMENT|ROLE=1");
    }

    @Test
    @DisplayName("within a set, types by count descending; ties ROLE, USER, GROUP")
    void typeOrderWithinSet() {
        JsonNode body = new Graph("s3", "b")
                .add("groups", 2, List.of("read"), null, false)
                .add("users", 2, List.of("read"), null, false)
                .add("roles", 1, List.of("read"), null, false)
                .build();
        assertThat(summary(grouper.group(body))).containsExactly("READ|USER=2", "READ|GROUP=2", "READ|ROLE=1");
    }

    @Test
    @DisplayName("list is ignored, non-boolean flags are false, and missing child keys are empty")
    void flagsAndMissingKeys() throws Exception {
        JsonNode body = json.readTree("""
                {"access_graph": {"type": "s3", "name": "b", "children": {
                  "users": [
                    {"name": "only-list", "list": true},
                    {"name": "string-flag", "read": "true", "write": 1},
                    {"name": "real", "read": true},
                    {"name": "blank-actions", "action": ["", null]}
                  ]}}}
                """);
        DatastoreAccessGraph graph = grouper.group(body);
        assertThat(graph.principalCount()).isEqualTo(1);
        assertThat(summary(graph)).containsExactly("READ|USER=1");
        assertThat(graph.groups().get(0).principals()).containsExactly("real");
    }

    @Test
    @DisplayName("principals sort case-insensitively, then naturally; a blank name is counted and listed as -")
    void principalSort() throws Exception {
        JsonNode body = json.readTree("""
                {"access_graph": {"children": {"roles": [
                  {"name": "beta", "read": true}, {"name": "Alpha", "read": true}, {"name": "alpha", "read": true},
                  {"name": "", "read": true}, {"name": "Charlie", "read": true}]}}}
                """);
        DatastoreAccessGraph graph = grouper.group(body);
        assertThat(graph.principalCount()).isEqualTo(5);
        assertThat(graph.groups().get(0).principals()).containsExactly("-", "Alpha", "alpha", "beta", "Charlie");
        assertThat(graph.rootName()).isNull();
    }

    @Test
    @DisplayName("an empty or missing graph gives 0 principals and no groups")
    void emptyGraph() throws Exception {
        assertThat(grouper.group(json.readTree("{}")).principalCount()).isZero();
        assertThat(grouper.group(json.readTree("{}")).groups()).isEmpty();
        assertThat(grouper.group(json.readTree("{\"access_graph\": {\"children\": {}}}")).groups()).isEmpty();
        assertThat(grouper.group(json.readTree("{\"access_graph\": null}")).principalCount()).isZero();
    }
}
