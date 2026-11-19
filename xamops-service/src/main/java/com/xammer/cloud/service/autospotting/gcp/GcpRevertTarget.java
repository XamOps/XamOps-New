package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a GCP REVERT restores (design-gcp.md §2.6 "restore(REVERT)", FR-REV-01 to FR-REV-04). A pure function of the
 * saved snapshot, the live MIG, the template registry and one read per snapshot template, so the plan, the PATCH, the
 * wait and the verify all agree and a resumed program re-derives the same target.
 *
 * <ul>
 *   <li><b>Exact</b>: the snapshot template still exists and its numeric {@code id} equals the saved one; the target
 *       is the original self-link.</li>
 *   <li><b>Rebuilt</b>: the snapshot template was deleted (or recreated under its name, which makes it not the
 *       original): a new template is built from the captured JSON, named {@code <migPrefix>-xas-<g8>-r<gen>},
 *       registered XamOps-owned ({@code role=REBUILT}); {@code exact=false} and the plan says so.</li>
 *   <li><b>Three-way</b>: a live standard template that XamOps does not own and that is not the snapshot's was
 *       changed (or accepted as the baseline) by the customer after enable; it is kept, and only the Spot version
 *       goes. The Spot version is always removed and the snapshot's {@code instanceFlexibilityPolicy} restored.</li>
 * </ul>
 * The rebuilt and the kept template never carry the XamOps attribution label (PO-20: it goes away on revert).
 */
public final class GcpRevertTarget {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String NOTE_REBUILT = "REVERT_TEMPLATE_REBUILT";
    public static final String NOTE_KEPT_CUSTOMER = "REVERT_KEPT_CUSTOMER_TEMPLATE";
    public static final String NOTE_SNAPSHOT_INEXACT = "SNAPSHOT_NOT_EXACT";
    public static final String UNAVAILABLE = "REVERT_TARGET_UNAVAILABLE";

    private GcpRevertTarget() {
    }

    /**
     * @param versions       the {@code versions[]} the PATCH sends
     * @param exact          true only when every template is the original (matching id) and nothing was kept
     * @param rebuild        true when a template must be rebuilt from the captured JSON
     * @param rebuildSource  the snapshot template self-link to rebuild (when {@code rebuild})
     * @param rebuildName    the name the rebuilt template gets
     * @param rebuiltLink    the self-link the rebuilt template will have
     * @param flexibility    the snapshot {@code instanceFlexibilityPolicy} to restore, or null
     * @param notes          the stable ids of what the plan must state
     * @param failure        a stable code when no safe target exists (before any mutation), else null
     */
    public record Resolved(ArrayNode versions, boolean exact, boolean rebuild, String rebuildSource, String rebuildName,
                           String rebuiltLink, JsonNode flexibility, List<String> notes, String failure) {
    }

    public static Resolved resolve(GcpStepServices services, GcpProgram p, GcpReadOnlyView view, GcpMigFacts live) {
        JsonNode snap = p.target();
        List<ObjectNode> snapVersions = snapshotVersions(snap);
        List<String> notes = new ArrayList<>();
        JsonNode flex = snap.path("instanceFlexibilityPolicy").isObject() && !snap.path("instanceFlexibilityPolicy").isEmpty()
                ? snap.path("instanceFlexibilityPolicy").deepCopy() : null;
        if (snapVersions.isEmpty()) {
            return new Resolved(JSON.createArrayNode(), false, false, null, null, null, flex, notes, UNAVAILABLE);
        }
        List<String> owned = services.registry().ownedLinks(p.groupId());
        String liveCustomer = liveCustomerTemplate(live, owned);
        ArrayNode out = JSON.createArrayNode();
        boolean exact = true;
        boolean rebuild = false;
        String rebuildSource = null;
        String rebuildName = null;
        String rebuiltLink = null;
        if (snapVersions.size() == 1) {
            ObjectNode sv = snapVersions.get(0);
            String snapLink = sv.path("instanceTemplate").asText();
            if (liveCustomer != null && !GcpTemplateRegistry.sameTemplate(liveCustomer, snapLink)) {
                sv.put("instanceTemplate", liveCustomer);
                sv.remove("targetSize");
                out.add(sv);
                exact = false;
                notes.add(NOTE_KEPT_CUSTOMER);
            } else if (isOriginal(view, snap, snapLink)) {
                out.add(sv);
            } else {
                Optional<GcpTemplateRegistry.Row> row = services.registry().findByOp(p.operationId());
                int generation = row.map(GcpTemplateRegistry.Row::generation)
                        .orElseGet(() -> services.registry().maxGeneration(p.groupId()) + 1);
                rebuildName = row.map(GcpTemplateRegistry.Row::name)
                        .orElseGet(() -> rebuildName(p.key().name(), p.groupId(), generation));
                String region = regionOf(snapLink);
                rebuiltLink = row.map(GcpTemplateRegistry.Row::selfLink)
                        .orElseGet(() -> templateSelfLink(p.key().project(), region, rebuildNameOf(p, generation)));
                if (templateResource(snap, snapLink) == null) {
                    return new Resolved(out, false, false, snapLink, null, null, flex, notes, UNAVAILABLE);
                }
                rebuild = true;
                rebuildSource = snapLink;
                sv.put("instanceTemplate", rebuiltLink);
                out.add(sv);
                exact = false;
                notes.add(NOTE_REBUILT);
            }
        } else {
            for (ObjectNode sv : snapVersions) {
                if (!isOriginal(view, snap, sv.path("instanceTemplate").asText())) {
                    return new Resolved(out, false, false, null, null, null, flex, notes, UNAVAILABLE);
                }
                out.add(sv);
            }
        }
        return new Resolved(out, exact, rebuild, rebuildSource, rebuildName, rebuiltLink, flex, notes, null);
    }

    private static String rebuildNameOf(GcpProgram p, int generation) {
        return rebuildName(p.key().name(), p.groupId(), generation);
    }

    /** {@code <migPrefix>-xas-<g8>-r<gen>}: at most 63 characters, RFC 1035. */
    public static String rebuildName(String migName, UUID groupId, int generation) {
        String suffix = "-xas-" + groupId.toString().replace("-", "").substring(0, 8) + "-r" + generation;
        String prefix = migName == null ? "mig" : migName;
        int room = 63 - suffix.length();
        if (prefix.length() > room) {
            prefix = prefix.substring(0, room);
        }
        while (prefix.endsWith("-")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + suffix;
    }

    /** The versions the snapshot recorded, without output-only fields; a single-template MIG becomes one unnamed version. */
    static List<ObjectNode> snapshotVersions(JsonNode snap) {
        List<ObjectNode> out = new ArrayList<>();
        JsonNode vs = snap.path("versions");
        if (vs.isArray() && vs.size() > 0) {
            for (JsonNode v : vs) {
                ObjectNode e = JSON.createObjectNode();
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
                    e.set("targetSize", size);
                }
                out.add(e);
            }
        } else if (snap.hasNonNull("instanceTemplate")) {
            ObjectNode e = JSON.createObjectNode();
            e.put("instanceTemplate", snap.path("instanceTemplate").asText());
            out.add(e);
        }
        return out;
    }

    /** The template the live standard version runs when XamOps does not own it, else null (nothing for the customer to keep). */
    private static String liveCustomerTemplate(GcpMigFacts live, List<String> owned) {
        String candidate = null;
        for (GcpMigFacts.Version v : live.versions()) {
            if (GcpMigFacts.STANDARD_VERSION.equals(v.name())) {
                candidate = v.instanceTemplate();
            }
        }
        if (candidate == null && live.versions().size() <= 1) {
            candidate = live.versions().isEmpty() ? live.instanceTemplate() : live.versions().get(0).instanceTemplate();
            if (live.versions().size() == 1 && GcpMigFacts.SPOT_VERSION.equals(live.versions().get(0).name())) {
                candidate = null;
            }
        }
        if (candidate == null) {
            return null;
        }
        final String c = candidate;
        return owned.stream().anyMatch(o -> GcpTemplateRegistry.sameTemplate(o, c)) ? null : c;
    }

    /** True when the snapshot template still exists and its numeric id is the saved one. */
    private static boolean isOriginal(GcpReadOnlyView view, JsonNode snap, String link) {
        String savedId = null;
        for (JsonNode t : snap.path("templates")) {
            if (GcpTemplateRegistry.sameTemplate(t.path("selfLink").asText(), link)) {
                savedId = t.path("id").asText(null);
            }
        }
        JsonNode cloud;
        try {
            cloud = view.reads().getInstanceTemplate(link);
        } catch (GcpReadException e) {
            if (e.kind() == ErrorKind.NOT_FOUND) {
                return false;
            }
            throw e;
        }
        return savedId == null || savedId.equals(cloud.path("id").asText(null));
    }

    /** The captured template resource of the snapshot, or null when it was not read. */
    public static JsonNode templateResource(JsonNode snap, String link) {
        for (JsonNode t : snap.path("templates")) {
            if (GcpTemplateRegistry.sameTemplate(t.path("selfLink").asText(), link) && t.path("resource").isObject()) {
                return t.path("resource");
            }
        }
        return null;
    }

    /** The rebuild: the captured properties, output-only fields dropped, the XamOps label never added or kept. */
    public static GcpTemplateCloner.Clone rebuild(JsonNode resource, String sourceName, String name, UUID groupId) {
        ObjectNode properties = resource.path("properties").deepCopy();
        GcpTemplateCloner.stripOutputOnly(properties);
        if (properties.path("labels").isObject()) {
            ObjectNode labels = (ObjectNode) properties.get("labels");
            labels.remove(GcpTemplateCloner.LABEL_KEY);
            if (labels.isEmpty()) {
                properties.remove("labels");
            }
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("name", name);
        body.put("description", "XamOps AutoSpotting rebuild of " + sourceName + " for group " + groupId);
        body.set("properties", properties);
        return new GcpTemplateCloner.Clone(body, name, GcpFingerprint.sha256(GcpTemplateCloner.canonical(body)));
    }

    /** True when {@code cloud} is the template this rebuild produced (same body, or our description marker for this group). */
    public static boolean isRebuilt(JsonNode cloud, String expectedSha, UUID groupId) {
        if (GcpTemplateCloner.sameBody(cloud, expectedSha)) {
            return true;
        }
        String d = cloud == null ? "" : cloud.path("description").asText("");
        return d.startsWith("XamOps AutoSpotting rebuild of ") && d.endsWith(" for group " + groupId);
    }

    static String regionOf(String templateLink) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/regions/([^/]+)/instanceTemplates/").matcher(templateLink);
        return m.find() ? m.group(1) : null;
    }

    static String templateSelfLink(String project, String region, String name) {
        return "https://www.googleapis.com/compute/v1/projects/" + project + (region == null ? "/global" : "/regions/" + region)
                + "/instanceTemplates/" + name;
    }
}
