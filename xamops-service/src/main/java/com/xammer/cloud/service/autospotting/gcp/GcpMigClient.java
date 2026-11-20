package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.config.HttpClient5Support;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only class holding the authorized GCP REST client for AutoSpotting (design-gcp.md §1.3,
 * design-core.md §4.3, §7.2). Credentials come from the existing {@link GcpClientProvider}; nothing
 * here stores or logs a token.
 *
 * <p>Two facades, so a planning path can never reach a mutation:
 * <ul>
 *   <li>{@link ReadOnly} ({@link #readOnly}): {@code get}, {@code list}, {@code aggregatedList} and
 *       {@code testIamPermissions} only. Reads use a 10 s connect / 30 s read budget.</li>
 *   <li>{@link Mutating} ({@link #mutating}): {@code instanceTemplates} insert and delete (global and
 *       regional) and the MIG {@code patch}, each with a {@code requestId} (the step's deterministic
 *       UUID, §2.7). 15 s connect / 45 s read. It has no caller until Phase 27.</li>
 * </ul>
 * Operations are never waited on here: {@link ReadOnly#getOperation} returns one poll, and the
 * caller polls as a step (never an unbounded {@code get()}). Errors become {@link GcpReadException}
 * ({@link GcpErrorMapper}); a 409 or 412 on PATCH is {@code CONFLICT}, never success (§2.7).
 */
@Component
public class GcpMigClient {
    static final String COMPUTE = "https://compute.googleapis.com/compute/v1";
    static final String RESOURCE_MANAGER = "https://cloudresourcemanager.googleapis.com/v1";
    static final String IAM = "https://iam.googleapis.com/v1";
    static final String CATALOG = "https://cloudbilling.googleapis.com/v1";
    /** The Compute Engine service id in the Cloud Billing Catalog. */
    public static final String COMPUTE_SERVICE_ID = "6F81-5844-456A";
    static final int MAX_PAGES = 50;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PROJECT = Pattern.compile("^[a-z][-a-z0-9]{4,28}[a-z0-9]$");
    private static final Pattern NAME = Pattern.compile("^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$");
    private static final Pattern LOCATION = Pattern.compile("^[a-z]+(-[a-z]+[0-9]+)(-[a-z])?$");
    private static final Pattern OPERATION_TYPE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern INSTANT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+(Z|[+-]\\d{2}:\\d{2})$");
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$");
    private static final Pattern TEMPLATE_LINK = Pattern.compile(
            "^https://(?:www|compute)\\.googleapis\\.com/compute/(?:v1|beta)/projects/([^/]+)/"
                    + "(?:global|regions/([^/]+))/instanceTemplates/([^/]+)$");

    /** Connect and read budgets, as the RetrySettings of design-core.md §4.3. */
    public record Timeouts(Duration connect, Duration read) {
    }

    public static final Timeouts READ_TIMEOUTS = new Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(30));
    public static final Timeouts MUTATION_TIMEOUTS = new Timeouts(Duration.ofSeconds(15), Duration.ofSeconds(45));

    private final Function<String, Optional<String>> tokens;
    private final RestTemplate readTemplate;
    private final RestTemplate mutationTemplate;

    @Autowired
    public GcpMigClient(GcpClientProvider credentials) {
        this(credentials::getAccessToken, build(READ_TIMEOUTS), build(MUTATION_TIMEOUTS));
    }

    GcpMigClient(Function<String, Optional<String>> tokens, RestTemplate readTemplate, RestTemplate mutationTemplate) {
        this.tokens = tokens;
        this.readTemplate = readTemplate;
        this.mutationTemplate = mutationTemplate;
    }

    private static RestTemplate build(Timeouts t) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setConnectionManager(HttpClient5Support.connectionManager((int) t.connect().toMillis()))
                        .build());
        factory.setReadTimeout((int) t.read().toMillis());
        return new RestTemplate(factory);
    }

    /** The read-only facade for {@code project}. */
    public ReadOnly readOnly(String project) {
        return new HttpReadOnly(requireProject(project));
    }

    /** The mutating facade for {@code project}. No caller until Phase 27. */
    public Mutating mutating(String project) {
        return new HttpMutating(requireProject(project));
    }

    // ---------------------------------------------------------------------------------------------
    // The facades

    /** Reads only. Every method is a {@code get}, {@code list}, {@code aggregatedList} or {@code testIamPermissions}. */
    public interface ReadOnly {
        String project();

        JsonNode getMig(boolean regional, String location, String name);

        /** Every MIG of the project, zonal and regional, from {@code instanceGroupManagers.aggregatedList}. */
        List<JsonNode> aggregatedListMigs();

        JsonNode getAutoscaler(String selfLinkOrPath);

        List<JsonNode> aggregatedListAutoscalers();

        /** A global or regional template by its self-link. */
        JsonNode getInstanceTemplate(String selfLink);

        JsonNode getMachineType(String zone, String machineType);

        JsonNode getRegion(String region);

        List<JsonNode> aggregatedListBackendServices();

        /** First page of per-instance configs (a stateful MIG has at least one). */
        List<JsonNode> listPerInstanceConfigs(boolean regional, String location, String name);

        List<JsonNode> listManagedInstances(boolean regional, String location, String name);

        List<JsonNode> listErrors(boolean regional, String location, String name);
        /**
         * Operations of one zone whose {@code operationType} is {@code operationType} and whose {@code insertTime} is
         * after {@code insertedAfter} (an RFC 3339 instant, or null for the first page of history), oldest first
         * (design-gcp.md §8.2 source 1: {@code compute.instances.preempted}). Read only.
         */
        List<JsonNode> listZoneOperations(String zone, String operationType, String insertedAfter);

        /** One poll of a zone, region or global operation ({@code scope} is {@code zones}, {@code regions} or {@code global}). */
        JsonNode getOperation(String scope, String location, String operationName);

        /** The granted subset of {@code permissions} on the project. */
        Set<String> testIamPermissions(List<String> permissions);

        /** The granted subset of {@code permissions} on one service account. */
        Set<String> testIamPermissions(String serviceAccountEmail, List<String> permissions);

        /** Cloud Billing Catalog SKUs of Compute Engine (all pages). */
        List<JsonNode> listCatalogSkus();
    }

    /** The mutations XamOps ever makes through GCP in AutoSpotting. Each carries a {@code requestId} (§2.7). */
    public interface Mutating {
        JsonNode insertInstanceTemplate(String regionOrNull, JsonNode body, UUID requestId);

        JsonNode deleteInstanceTemplate(String regionOrNull, String templateName, UUID requestId);

        /** PATCH a MIG with the raw MIG {@code fingerprint} as its precondition. 409 and 412 are CONFLICT. */
        JsonNode patchMig(boolean regional, String location, String name, JsonNode body, String fingerprint, UUID requestId);
    }

    // ---------------------------------------------------------------------------------------------
    // Read implementation

    private final class HttpReadOnly implements ReadOnly {
        private final String project;

        HttpReadOnly(String project) {
            this.project = project;
        }

        @Override
        public String project() {
            return project;
        }

        @Override
        public JsonNode getMig(boolean regional, String location, String name) {
            return call(project, "instanceGroupManagers.get", readTemplate, HttpMethod.GET,
                    compute(regional ? "regions" : "zones", location, "instanceGroupManagers", name), null);
        }

        @Override
        public List<JsonNode> aggregatedListMigs() {
            return aggregated("instanceGroupManagers.aggregatedList", "/aggregated/instanceGroupManagers",
                    "instanceGroupManagers");
        }

        @Override
        public JsonNode getAutoscaler(String selfLinkOrPath) {
            // An autoscaler link names its own scope: .../zones/z/autoscalers/n or .../regions/r/autoscalers/n.
            Matcher m = Pattern.compile("(?:^|/)(zones|regions)/([^/]+)/autoscalers/([^/]+)$").matcher(selfLinkOrPath);
            if (!m.find()) {
                throw new GcpReadException("autoscalers.get", ErrorKind.VALIDATION);
            }
            return call(project, "autoscalers.get", readTemplate, HttpMethod.GET,
                    compute(m.group(1), m.group(2), "autoscalers", m.group(3)), null);
        }

        @Override
        public List<JsonNode> aggregatedListAutoscalers() {
            return aggregated("autoscalers.aggregatedList", "/aggregated/autoscalers", "autoscalers");
        }

        @Override
        public JsonNode getInstanceTemplate(String selfLink) {
            Matcher m = TEMPLATE_LINK.matcher(selfLink == null ? "" : selfLink);
            if (!m.matches()) {
                throw new GcpReadException("instanceTemplates.get", ErrorKind.VALIDATION);
            }
            String templateProject = requireProject(m.group(1));
            String region = m.group(2);
            String name = requireName(m.group(3));
            URI uri = region == null
                    ? build(COMPUTE + "/projects/{p}/global/instanceTemplates/{n}", templateProject, name)
                    : build(COMPUTE + "/projects/{p}/regions/{r}/instanceTemplates/{n}", templateProject,
                            requireLocation(region), name);
            return call(project, "instanceTemplates.get", readTemplate, HttpMethod.GET, uri, null);
        }

        @Override
        public JsonNode getMachineType(String zone, String machineType) {
            return call(project, "machineTypes.get", readTemplate, HttpMethod.GET,
                    compute("zones", zone, "machineTypes", machineType), null);
        }

        @Override
        public JsonNode getRegion(String region) {
            return call(project, "regions.get", readTemplate, HttpMethod.GET,
                    build(COMPUTE + "/projects/{p}/regions/{r}", project, requireLocation(region)), null);
        }

        @Override
        public List<JsonNode> aggregatedListBackendServices() {
            return aggregated("backendServices.aggregatedList", "/aggregated/backendServices", "backendServices");
        }

        @Override
        public List<JsonNode> listPerInstanceConfigs(boolean regional, String location, String name) {
            // The v1 method is a POST with an empty body; it reads.
            JsonNode page = call(project, "instanceGroupManagers.listPerInstanceConfigs", readTemplate, HttpMethod.POST,
                    UriComponentsBuilder.fromUri(compute(regional ? "regions" : "zones", location,
                            "instanceGroupManagers", name)).path("/listPerInstanceConfigs")
                            .queryParam("maxResults", 50).build().encode().toUri(), JSON.createObjectNode());
            return items(page.path("items"));
        }

        @Override
        public List<JsonNode> listManagedInstances(boolean regional, String location, String name) {
            List<JsonNode> out = new ArrayList<>();
            String token = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                UriComponentsBuilder b = UriComponentsBuilder.fromUri(compute(regional ? "regions" : "zones", location,
                        "instanceGroupManagers", name)).path("/listManagedInstances").queryParam("maxResults", 500);
                if (token != null) {
                    b.queryParam("pageToken", token);
                }
                JsonNode resp = call(project, "instanceGroupManagers.listManagedInstances", readTemplate, HttpMethod.POST,
                        b.build().encode().toUri(), JSON.createObjectNode());
                out.addAll(items(resp.path("managedInstances")));
                token = resp.path("nextPageToken").asText(null);
                if (token == null || token.isEmpty()) {
                    return out;
                }
            }
            throw new GcpReadException("instanceGroupManagers.listManagedInstances", ErrorKind.TRANSIENT);
        }

        @Override
        public List<JsonNode> listErrors(boolean regional, String location, String name) {
            URI uri = UriComponentsBuilder.fromUri(compute(regional ? "regions" : "zones", location,
                    "instanceGroupManagers", name)).path("/listErrors").queryParam("maxResults", 50).build().encode().toUri();
            return items(call(project, "instanceGroupManagers.listErrors", readTemplate, HttpMethod.GET, uri, null).path("items"));
        }

        @Override
        public List<JsonNode> listZoneOperations(String zone, String operationType, String insertedAfter) {
            requireLocation(zone);
            if (operationType == null || !OPERATION_TYPE.matcher(operationType).matches()) {
                throw new GcpReadException("zoneOperations.list", ErrorKind.VALIDATION);
            }
            if (insertedAfter != null && !INSTANT.matcher(insertedAfter).matches()) {
                throw new GcpReadException("zoneOperations.list", ErrorKind.VALIDATION);
            }
            String filter = "operationType=\"" + operationType + "\""
                    + (insertedAfter == null ? "" : " AND insertTime>\"" + insertedAfter + "\"");
            List<JsonNode> out = new ArrayList<>();
            String token = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                UriComponentsBuilder b = UriComponentsBuilder.fromUriString(COMPUTE + "/projects/" + project + "/zones/"
                        + zone + "/operations").queryParam("maxResults", 500).queryParam("filter", filter);
                if (token != null) {
                    b.queryParam("pageToken", token);
                }
                JsonNode resp = call(project, "zoneOperations.list", readTemplate, HttpMethod.GET,
                        b.build().encode().toUri(), null);
                out.addAll(items(resp.path("items")));
                token = resp.path("nextPageToken").asText(null);
                if (token == null || token.isEmpty()) {
                    out.sort(java.util.Comparator.comparing(o -> o.path("insertTime").asText("")));
                    return out;
                }
            }
            throw new GcpReadException("zoneOperations.list", ErrorKind.TRANSIENT);
        }
        @Override
        public JsonNode getOperation(String scope, String location, String operationName) {
            requireName(operationName);
            if ("global".equals(scope)) {
                return call(project, "globalOperations.get", readTemplate, HttpMethod.GET,
                        build(COMPUTE + "/projects/{p}/global/operations/{o}", project, operationName), null);
            }
            if (!"zones".equals(scope) && !"regions".equals(scope)) {
                throw new GcpReadException("operations.get", ErrorKind.VALIDATION);
            }
            return call(project, scope + ".operations.get", readTemplate, HttpMethod.GET,
                    compute(scope, location, "operations", operationName), null);
        }

        @Override
        public Set<String> testIamPermissions(List<String> permissions) {
            ObjectNode body = JSON.createObjectNode();
            ArrayNode arr = body.putArray("permissions");
            permissions.forEach(arr::add);
            JsonNode resp = call(project, "projects.testIamPermissions", readTemplate, HttpMethod.POST,
                    build(RESOURCE_MANAGER + "/projects/{p}:testIamPermissions", project), body);
            return granted(resp);
        }

        @Override
        public Set<String> testIamPermissions(String serviceAccountEmail, List<String> permissions) {
            if (serviceAccountEmail == null || !EMAIL.matcher(serviceAccountEmail).matches()) {
                throw new GcpReadException("serviceAccounts.testIamPermissions", ErrorKind.VALIDATION);
            }
            ObjectNode body = JSON.createObjectNode();
            ArrayNode arr = body.putArray("permissions");
            permissions.forEach(arr::add);
            JsonNode resp = call(project, "serviceAccounts.testIamPermissions", readTemplate, HttpMethod.POST,
                    build(IAM + "/projects/{p}/serviceAccounts/{e}:testIamPermissions", project, serviceAccountEmail), body);
            return granted(resp);
        }

        @Override
        public List<JsonNode> listCatalogSkus() {
            List<JsonNode> out = new ArrayList<>();
            String token = null;
            for (int page = 0; page < MAX_PAGES * 4; page++) {
                UriComponentsBuilder b = UriComponentsBuilder.fromUriString(CATALOG + "/services/" + COMPUTE_SERVICE_ID
                        + "/skus").queryParam("pageSize", 5000).queryParam("currencyCode", "USD");
                if (token != null) {
                    b.queryParam("pageToken", token);
                }
                JsonNode resp = call(project, "skus.list", readTemplate, HttpMethod.GET, b.build().encode().toUri(), null);
                out.addAll(items(resp.path("skus")));
                token = resp.path("nextPageToken").asText(null);
                if (token == null || token.isEmpty()) {
                    return out;
                }
            }
            throw new GcpReadException("skus.list", ErrorKind.TRANSIENT);
        }

        private URI compute(String scope, String location, String collection, String name) {
            return build(COMPUTE + "/projects/{p}/" + scope + "/{l}/" + collection + "/{n}", project,
                    requireLocation(location), requireName(name));
        }

        private List<JsonNode> aggregated(String call, String path, String listKey) {
            List<JsonNode> out = new ArrayList<>();
            String token = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                UriComponentsBuilder b = UriComponentsBuilder.fromUriString(COMPUTE + "/projects/" + project + path)
                        .queryParam("maxResults", 500);
                if (token != null) {
                    b.queryParam("pageToken", token);
                }
                JsonNode resp = call(project, call, readTemplate, HttpMethod.GET, b.build().encode().toUri(), null);
                resp.path("items").fields().forEachRemaining(scope -> out.addAll(items(scope.getValue().path(listKey))));
                token = resp.path("nextPageToken").asText(null);
                if (token == null || token.isEmpty()) {
                    return out;
                }
            }
            throw new GcpReadException(call, ErrorKind.TRANSIENT);
        }

        private Set<String> granted(JsonNode resp) {
            Set<String> out = new HashSet<>();
            resp.path("permissions").forEach(p -> out.add(p.asText()));
            return out;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Mutation implementation

    private final class HttpMutating implements Mutating {
        private final String project;

        HttpMutating(String project) {
            this.project = project;
        }

        @Override
        public JsonNode insertInstanceTemplate(String regionOrNull, JsonNode body, UUID requestId) {
            URI uri = regionOrNull == null
                    ? withRequestId(build(COMPUTE + "/projects/{p}/global/instanceTemplates", project), requestId)
                    : withRequestId(build(COMPUTE + "/projects/{p}/regions/{r}/instanceTemplates", project,
                            requireLocation(regionOrNull)), requestId);
            return call(project, "instanceTemplates.insert", mutationTemplate, HttpMethod.POST, uri, body);
        }

        @Override
        public JsonNode deleteInstanceTemplate(String regionOrNull, String templateName, UUID requestId) {
            requireName(templateName);
            URI uri = regionOrNull == null
                    ? withRequestId(build(COMPUTE + "/projects/{p}/global/instanceTemplates/{n}", project,
                            templateName), requestId)
                    : withRequestId(build(COMPUTE + "/projects/{p}/regions/{r}/instanceTemplates/{n}", project,
                            requireLocation(regionOrNull), templateName), requestId);
            return call(project, "instanceTemplates.delete", mutationTemplate, HttpMethod.DELETE, uri, null);
        }

        @Override
        public JsonNode patchMig(boolean regional, String location, String name, JsonNode body, String fingerprint,
                                 UUID requestId) {
            ObjectNode patch = body == null || !body.isObject() ? JSON.createObjectNode() : ((ObjectNode) body).deepCopy();
            if (fingerprint != null) {
                patch.put("fingerprint", fingerprint);
            }
            URI base = build(COMPUTE + "/projects/{p}/" + (regional ? "regions" : "zones") + "/{l}/instanceGroupManagers/{n}",
                    project, requireLocation(location), requireName(name));
            return call(project, "instanceGroupManagers.patch", mutationTemplate, HttpMethod.PATCH, withRequestId(base, requestId), patch);
        }

        private URI withRequestId(URI uri, UUID requestId) {
            if (requestId == null) {
                throw new GcpReadException("requestId", ErrorKind.VALIDATION);
            }
            return UriComponentsBuilder.fromUri(uri).queryParam("requestId", requestId).build().encode().toUri();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared

    /** Send one request with the project's bearer token; any failure becomes a mapped {@link GcpReadException}. */
    private JsonNode call(String project, String name, RestTemplate template, HttpMethod method, URI uri, JsonNode body) {
        String token;
        try {
            token = tokens.apply(project).orElse(null);
        } catch (RuntimeException e) {
            throw new GcpReadException(name, ErrorKind.PERMISSION);
        }
        if (token == null || token.isBlank()) {
            throw new GcpReadException(name, ErrorKind.PERMISSION);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            String raw = template.exchange(uri, method, new HttpEntity<>(body == null ? null : body.toString(), headers),
                    String.class).getBody();
            return raw == null || raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
        } catch (java.io.IOException e) {
            throw new GcpReadException(name, ErrorKind.TRANSIENT);
        } catch (RuntimeException e) {
            throw GcpErrorMapper.wrap(name, e);
        }
    }

    private static URI build(String template, Object... vars) {
        return UriComponentsBuilder.fromUriString(template).buildAndExpand(vars).encode().toUri();
    }

    private static List<JsonNode> items(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }

    static String requireProject(String project) {
        if (project == null || !PROJECT.matcher(project).matches()) {
            throw new GcpReadException("project", ErrorKind.VALIDATION);
        }
        return project;
    }

    static String requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new GcpReadException("name", ErrorKind.VALIDATION);
        }
        return name;
    }

    static String requireLocation(String location) {
        if (location == null || !LOCATION.matcher(location).matches()) {
            throw new GcpReadException("location", ErrorKind.VALIDATION);
        }
        return location;
    }
}
