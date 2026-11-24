package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory {@link GcpMigClient.ReadOnly} over recorded-shape fixtures, with per-call fault injection
 * and a call log. No test using it calls GCP. It implements only the read facade, so a mutating call
 * cannot even be written against it.
 */
final class FakeGcp implements GcpMigClient.ReadOnly {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PROJECT = "xamops-test";
    static final String P = "https://www.googleapis.com/compute/v1/projects/" + PROJECT;
    static final String TEMPLATE = P + "/global/instanceTemplates/web-tpl-1";

    final List<String> calls = new ArrayList<>();
    final Map<String, ErrorKind> faults = new HashMap<>();
    final List<JsonNode> migs = new ArrayList<>();
    final List<JsonNode> autoscalers = new ArrayList<>();
    final Map<String, JsonNode> templates = new HashMap<>();
    final Map<String, JsonNode> machineTypes = new HashMap<>();
    final Map<String, JsonNode> regions = new HashMap<>();
    final List<JsonNode> backendServices = new ArrayList<>();
    final List<JsonNode> perInstanceConfigs = new ArrayList<>();
    final List<JsonNode> managedInstances = new ArrayList<>();
    final List<JsonNode> errors = new ArrayList<>();
    /** Zone operations by zone name (any operationType; the fake filters like the API does). */
    final Map<String, List<JsonNode>> zoneOperations = new HashMap<>();
    final Set<String> grantedProject = new HashSet<>(GcpEligibilityEvaluator.REQUIRED_PERMISSIONS);
    final Set<String> actAsDenied = new HashSet<>();
    List<JsonNode> skus = new ArrayList<>();

    static JsonNode fixture(String name) {
        try (InputStream in = FakeGcp.class.getResourceAsStream("/autospotting/gcp/" + name)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(name, e);
        }
    }

    static ObjectNode obj(String name) {
        return (ObjectNode) fixture(name).deepCopy();
    }

    /** A healthy regional MIG on a plain template: eligible at the conservative profile. */
    static FakeGcp healthy() {
        FakeGcp f = new FakeGcp();
        f.migs.add(fixture("mig-regional-web.json"));
        f.templates.put(TEMPLATE, fixture("template-web.json"));
        f.machineTypes.put("n2-standard-4", fixture("machinetype-n2-standard-4.json"));
        f.regions.put("us-central1", fixture("region-us-central1.json"));
        f.managedInstances.addAll(arr(fixture("managed-instances.json")));
        f.skus = arr(fixture("listskus-compute.json").path("skus"));
        return f;
    }

    static List<JsonNode> arr(JsonNode a) {
        List<JsonNode> out = new ArrayList<>();
        a.forEach(out::add);
        return out;
    }

    FakeGcp fail(String call, ErrorKind kind) {
        faults.put(call, kind);
        return this;
    }

    private void hit(String call) {
        calls.add(call);
        ErrorKind k = faults.get(call);
        if (k != null) {
            throw new GcpReadException(call, k);
        }
    }

    GcpReadOnlyView view() {
        return new GcpReadOnlyView(PROJECT, this);
    }

    @Override
    public String project() {
        return PROJECT;
    }

    @Override
    public JsonNode getMig(boolean regional, String location, String name) {
        hit("instanceGroupManagers.get");
        return migs.stream().filter(m -> name.equals(m.path("name").asText())).findFirst()
                .orElseThrow(() -> new GcpReadException("instanceGroupManagers.get", ErrorKind.NOT_FOUND, 404));
    }

    @Override
    public List<JsonNode> aggregatedListMigs() {
        hit("instanceGroupManagers.aggregatedList");
        return List.copyOf(migs);
    }

    @Override
    public JsonNode getAutoscaler(String link) {
        hit("autoscalers.get");
        return autoscalers.stream().filter(a -> link.equals(a.path("selfLink").asText())).findFirst()
                .orElseThrow(() -> new GcpReadException("autoscalers.get", ErrorKind.NOT_FOUND, 404));
    }

    @Override
    public List<JsonNode> aggregatedListAutoscalers() {
        hit("autoscalers.aggregatedList");
        return List.copyOf(autoscalers);
    }

    @Override
    public JsonNode getInstanceTemplate(String selfLink) {
        hit("instanceTemplates.get");
        JsonNode t = templates.get(selfLink);
        if (t == null) {
            throw new GcpReadException("instanceTemplates.get", ErrorKind.NOT_FOUND, 404);
        }
        return t;
    }

    @Override
    public JsonNode getMachineType(String zone, String machineType) {
        hit("machineTypes.get");
        JsonNode m = machineTypes.get(machineType);
        if (m == null) {
            throw new GcpReadException("machineTypes.get", ErrorKind.NOT_FOUND, 404);
        }
        return m;
    }

    @Override
    public JsonNode getRegion(String region) {
        hit("regions.get");
        JsonNode r = regions.get(region);
        if (r == null) {
            throw new GcpReadException("regions.get", ErrorKind.NOT_FOUND, 404);
        }
        return r;
    }

    @Override
    public List<JsonNode> aggregatedListBackendServices() {
        hit("backendServices.aggregatedList");
        return List.copyOf(backendServices);
    }

    @Override
    public List<JsonNode> listPerInstanceConfigs(boolean regional, String location, String name) {
        hit("instanceGroupManagers.listPerInstanceConfigs");
        return List.copyOf(perInstanceConfigs);
    }

    @Override
    public List<JsonNode> listManagedInstances(boolean regional, String location, String name) {
        hit("instanceGroupManagers.listManagedInstances");
        return List.copyOf(managedInstances);
    }

    @Override
    public List<JsonNode> listErrors(boolean regional, String location, String name) {
        hit("instanceGroupManagers.listErrors");
        return List.copyOf(errors);
    }

    @Override
    public List<JsonNode> listZoneOperations(String zone, String operationType, String insertedAfter) {
        hit("zoneOperations.list");
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode o : zoneOperations.getOrDefault(zone, List.of())) {
            if (operationType.equals(o.path("operationType").asText())
                    && (insertedAfter == null || o.path("insertTime").asText("").compareTo(insertedAfter) > 0)) {
                out.add(o);
            }
        }
        out.sort(java.util.Comparator.comparing(o -> o.path("insertTime").asText("")));
        return out;
    }
    @Override
    public JsonNode getOperation(String scope, String location, String operationName) {
        hit("operations.get");
        return JSON.createObjectNode().put("status", "DONE");
    }

    @Override
    public Set<String> testIamPermissions(List<String> permissions) {
        hit("projects.testIamPermissions");
        Set<String> out = new HashSet<>(permissions);
        out.retainAll(grantedProject);
        return out;
    }

    @Override
    public Set<String> testIamPermissions(String serviceAccountEmail, List<String> permissions) {
        hit("serviceAccounts.testIamPermissions");
        return actAsDenied.contains(serviceAccountEmail) ? Set.of() : new HashSet<>(permissions);
    }

    @Override
    public List<JsonNode> listCatalogSkus() {
        hit("skus.list");
        return List.copyOf(skus);
    }

    /** Count of reads of {@code call}. */
    long count(String call) {
        return calls.stream().filter(call::equals).count();
    }

    /** Mutate the first MIG in place. */
    ObjectNode mig() {
        return (ObjectNode) migs.get(0);
    }

    ObjectNode template() {
        return (ObjectNode) templates.get(TEMPLATE);
    }

    static ArrayNode array() {
        return JSON.createArrayNode();
    }
}
