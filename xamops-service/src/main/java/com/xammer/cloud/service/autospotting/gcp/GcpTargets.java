package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a program's PATCH sets and what a rollout must reach: the versions, the template each version maps to,
 * and a convergence test ("does the live MIG already show these versions"), shared by the PATCH, the wait and
 * the probe so they cannot disagree. A forward conversion maps {@code xas-standard} to the original template
 * and {@code xas-spot} to the registry's Spot copy; a compensation maps to the prior configuration's templates.
 */
public final class GcpTargets {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern OPERATION = Pattern.compile("/(global|regions/([^/]+)|zones/([^/]+))/operations/([^/?]+)$");

    private GcpTargets() {
    }

    /** The {@code versions[]} to PATCH. */
    public static ArrayNode versions(GcpProgram p, String originalTemplate, String spotTemplate) {
        if (!p.compensation()) {
            return GcpFloorPlanner.versions(p.floorN(), originalTemplate, spotTemplate);
        }
        ArrayNode out = JSON.createArrayNode();
        JsonNode vs = p.target().path("versions");
        if (vs.isArray() && vs.size() > 0) {
            for (JsonNode v : vs) {
                ObjectNode e = out.addObject();
                if (v.hasNonNull("name")) {
                    e.put("name", v.path("name").asText());
                }
                e.put("instanceTemplate", v.path("instanceTemplate").asText());
                ObjectNode size = JSON.createObjectNode();
                if (v.path("targetSize").path("fixed").isNumber()) {
                    size.put("fixed", v.path("targetSize").path("fixed").asInt());
                } else if (v.path("targetSize").path("percent").isNumber()) {
                    size.put("percent", v.path("targetSize").path("percent").asInt());
                }
                if (!size.isEmpty()) {
                    e.set("targetSize", size); // "calculated" is output only and never sent
                }
            }
        } else {
            out.addObject().put("instanceTemplate", p.target().path("instanceTemplate").asText());
        }
        return out;
    }

    /** Version name to the template its instances must run ("" for an unnamed version). */
    public static Map<String, String> expected(ArrayNode versions) {
        Map<String, String> m = new LinkedHashMap<>();
        for (JsonNode v : versions) {
            m.put(v.path("name").asText(""), v.path("instanceTemplate").asText());
        }
        return m;
    }

    public static int floorOf(ArrayNode versions) {
        for (JsonNode v : versions) {
            if (GcpMigFacts.STANDARD_VERSION.equals(v.path("name").asText())) {
                return v.path("targetSize").path("fixed").asInt(0);
            }
        }
        return 0;
    }

    /** True when the live MIG's versions are exactly {@code versions} (name, template, fixed size). */
    public static boolean matches(GcpMigFacts g, ArrayNode versions) {
        java.util.List<GcpMigFacts.Version> live = g.versions();
        if (live.isEmpty() && g.instanceTemplate() != null) {
            live = java.util.List.of(new GcpMigFacts.Version(null, g.instanceTemplate(), null, null));
        }
        if (live.size() != versions.size()) {
            return false;
        }
        for (JsonNode want : versions) {
            String name = want.path("name").asText("");
            boolean found = live.stream().anyMatch(v -> (v.name() == null ? "" : v.name()).equals(name)
                    && GcpTemplateRegistry.sameTemplate(v.instanceTemplate(), want.path("instanceTemplate").asText())
                    && (want.path("targetSize").path("fixed").isNumber()
                    ? Integer.valueOf(want.path("targetSize").path("fixed").asInt()).equals(v.targetSizeFixed())
                    : v.targetSizeFixed() == null));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** True when every field of {@code want} equals the same field of {@code live}. */
    public static boolean policyHas(JsonNode live, JsonNode want) {
        if (want == null || !want.isObject()) {
            return true;
        }
        var it = want.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().isObject()) {
                if (!policyHas(live.path(e.getKey()), e.getValue())) {
                    return false;
                }
            } else if (!e.getValue().equals(live.path(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** {scope, location, name} of an Operation's self-link, or null. */
    public static String[] operationRef(String selfLink) {
        if (selfLink == null) {
            return null;
        }
        Matcher m = OPERATION.matcher(selfLink);
        if (!m.find()) {
            return null;
        }
        if ("global".equals(m.group(1))) {
            return new String[] {"global", null, m.group(4)};
        }
        return m.group(2) != null ? new String[] {"regions", m.group(2), m.group(4)}
                : new String[] {"zones", m.group(3), m.group(4)};
    }
}
