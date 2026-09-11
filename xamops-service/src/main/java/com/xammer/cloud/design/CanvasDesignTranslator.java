package com.xammer.cloud.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.design.catalog.ResourceCatalog;
import com.xammer.cloud.design.catalog.ResourceTypeSpec;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.AttributeKind;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.AttributeSpec;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.ReferenceSpec;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.ReferenceVia;
import com.xammer.cloud.design.model.DesignModel;
import com.xammer.cloud.design.model.DesignModel.ResourceNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a saved CloudMap canvas and produces a {@link DesignModel}.
 *
 * <p>
 * This is the compatibility layer, and it is why adding Terraform generation does
 * not disturb the existing diagram editor. It consumes
 * {@code architecture_blueprints.graph_data} exactly as the canvas already writes
 * it — {@code JSON.stringify(cy.elements().jsons())} — so there is no migration,
 * no new save format, and no change to how anyone draws.
 *
 * <p>
 * <strong>Anything it does not recognise, it skips and reports.</strong> The
 * design canvas is a general diagramming tool: people put generic rectangles,
 * freehand strokes, text labels and custom icons on it. Those are not resources
 * and must never become errors, or a drawing tool would start refusing drawings.
 * A node contributes Terraform only if its {@code type} maps to a catalog entry;
 * everything else is listed in {@link Result#skipped()} so the UI can say "12 of
 * 20 shapes are generatable" rather than failing.
 *
 * <p>
 * Two pieces of leniency are deliberate, because the canvas was built for
 * pictures rather than for code:
 * <ul>
 * <li><b>Ancestor walking.</b> Containment resolves through non-resource
 * containers, so a subnet inside a decorative group inside a VPC still yields
 * {@code vpc_id}.</li>
 * <li><b>Direction tolerance.</b> An edge is matched in either direction. Nobody
 * drawing a picture thinks about whether the arrow goes instance-to-group or
 * group-to-instance, and Terraform only cares which resource holds the
 * attribute.</li>
 * </ul>
 */
@Service
public class CanvasDesignTranslator {

    private static final Logger logger = LoggerFactory.getLogger(CanvasDesignTranslator.class);

    /**
     * Canvas-only data keys. Never candidates for Terraform attributes.
     *
     * <p>
     * The canvas stores presentation on the same objects as meaning, so the
     * translator has to filter. Missing one here would emit something like
     * {@code fillColor = "#f97316"} into a resource block.
     */
    private static final Set<String> PRESENTATION_KEYS = Set.of(
            "id", "label", "parent", "type", "source", "target", "kind",
            "isContainer", "containerType", "customIcon", "_iconUrl", "iconUrl",
            "fillColor", "fillColor2", "gradientDir", "borderColor", "borderWidth", "borderDash",
            "nodeShape", "nodeShadow", "shadowBlur", "shadowOffsetX", "shadowOffsetY", "shadowColor",
            "fontSize", "fontBold", "fontItalic", "fontFamily", "fontColor", "textValign", "textHalign",
            "edgeStyle", "arrowType", "edgeWidth", "edgeDash", "opacity", "locked", "tooltip",
            "pulse", "flowAnimation", "flowSpeed", "sourceLabel", "targetLabel", "midArrowShape",
            "sourcePort", "targetPort", "taxiDir", "rotation", "flipH", "flipV",
            "hyperlink", "metadata", "comments", "controlPointDistance",
            "width", "height", "isTableRow", "state", "region");

    private final ObjectMapper objectMapper;
    private final ResourceCatalog catalog;

    public CanvasDesignTranslator(ObjectMapper objectMapper, ResourceCatalog catalog) {
        this.objectMapper = objectMapper;
        this.catalog = catalog;
    }

    /**
     * @param graphData raw Cytoscape element JSON as saved by the canvas
     * @param name      design name, used for the module header comment
     * @param region    target region for the provider block
     */
    public Result translate(String graphData, String name, String region) {
        if (graphData == null || graphData.isBlank()) {
            return new Result(new DesignModel(name, region, List.of()), List.of(), List.of());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(graphData);
        } catch (Exception e) {
            throw new IllegalArgumentException("Blueprint graph data is not valid JSON", e);
        }

        List<JsonNode> elements = flattenElements(root);

        // Pass 1: index every canvas node, resource or not, so containment can walk
        // through decorative containers.
        Map<String, CanvasNode> canvasNodes = new LinkedHashMap<>();
        for (JsonNode element : elements) {
            JsonNode data = element.path("data");
            String id = data.path("id").asText(null);
            if (id == null || isEdge(data)) {
                continue;
            }
            canvasNodes.put(id, new CanvasNode(
                    id,
                    data.path("label").asText(""),
                    data.path("type").asText(null),
                    data.hasNonNull("parent") ? data.get("parent").asText() : null,
                    data));
        }

        // Pass 2: keep the ones the catalog knows how to generate.
        Map<String, ResolvedNode> resolved = new LinkedHashMap<>();
        List<Skipped> skipped = new ArrayList<>();
        for (CanvasNode node : canvasNodes.values()) {
            Optional<ResourceTypeSpec> spec = specFor(node);
            if (spec.isEmpty()) {
                skipped.add(new Skipped(node.id(), node.label(), node.type(),
                        node.type() == null || node.type().isBlank()
                                ? "Not a cloud resource (a shape, text or drawing)."
                                : "'" + node.type() + "' is not in the Terraform catalog yet."));
                continue;
            }
            resolved.put(node.id(), new ResolvedNode(node, spec.get()));
        }

        // Pass 3: containment references, walking past non-resource ancestors.
        Map<String, Map<String, List<String>>> references = new LinkedHashMap<>();
        resolved.keySet().forEach(id -> references.put(id, new LinkedHashMap<>()));

        for (ResolvedNode node : resolved.values()) {
            String ancestorId = nearestResourceAncestor(node.canvas(), canvasNodes, resolved);
            if (ancestorId == null) {
                continue;
            }
            ResolvedNode ancestor = resolved.get(ancestorId);
            node.spec().references().stream()
                    .filter(r -> r.via() == ReferenceVia.CONTAINMENT)
                    .filter(r -> r.targetTfType().equals(ancestor.spec().tfType()))
                    .findFirst()
                    .ifPresent(r -> references.get(node.canvas().id())
                            .computeIfAbsent(r.field(), k -> new ArrayList<>())
                            .add(ancestorId));
        }

        // Pass 4: edge references, accepted in either direction.
        List<String> notes = new ArrayList<>();
        for (JsonNode element : elements) {
            JsonNode data = element.path("data");
            if (!isEdge(data)) {
                continue;
            }
            String source = data.path("source").asText(null);
            String target = data.path("target").asText(null);
            if (source == null || target == null) {
                continue;
            }
            ResolvedNode a = resolved.get(source);
            ResolvedNode b = resolved.get(target);
            if (a == null || b == null) {
                // An edge touching a decorative shape is a drawing, not a relationship.
                continue;
            }

            if (!linkEdge(a, b, references) && !linkEdge(b, a, references)) {
                notes.add("No Terraform relationship exists between %s (%s) and %s (%s); the connection is decorative."
                        .formatted(a.canvas().label(), a.spec().displayName(),
                                b.canvas().label(), b.spec().displayName()));
            }
        }

        // Pass 5: build the model.
        List<ResourceNode> nodes = new ArrayList<>();
        for (ResolvedNode node : resolved.values()) {
            Map<String, List<String>> refs = references.get(node.canvas().id());
            nodes.add(new ResourceNode(
                    node.canvas().id(),
                    node.spec().tfType(),
                    resourceName(node),
                    readAttributes(node),
                    refs == null ? Map.of() : refs));
        }

        logger.debug("Translated canvas: {} resources, {} skipped", nodes.size(), skipped.size());
        return new Result(new DesignModel(name, region, nodes), skipped, notes);
    }

    /**
     * Wire an edge as {@code owner.field -> other}, if the catalog allows it.
     *
     * @return true when a relationship was recorded
     */
    private boolean linkEdge(ResolvedNode owner, ResolvedNode other,
                             Map<String, Map<String, List<String>>> references) {
        Optional<ReferenceSpec> match = owner.spec().references().stream()
                .filter(r -> r.via() == ReferenceVia.EDGE)
                .filter(r -> r.targetTfType().equals(other.spec().tfType()))
                .findFirst();
        if (match.isEmpty()) {
            return false;
        }
        ReferenceSpec ref = match.get();
        List<String> targets = references.get(owner.canvas().id())
                .computeIfAbsent(ref.field(), k -> new ArrayList<>());
        if (!ref.list() && !targets.isEmpty()) {
            // A scalar field already claimed; a second edge would silently overwrite.
            return true;
        }
        if (!targets.contains(other.canvas().id())) {
            targets.add(other.canvas().id());
        }
        return true;
    }

    /**
     * Walk up {@code parent} until a catalog-backed node appears.
     *
     * <p>
     * People group things visually. Without this, dropping a subnet inside a
     * labelled group box inside a VPC would lose {@code vpc_id} and the design
     * would fail validation for a reason invisible on screen.
     */
    private String nearestResourceAncestor(CanvasNode node, Map<String, CanvasNode> all,
                                           Map<String, ResolvedNode> resolved) {
        Set<String> seen = new LinkedHashSet<>();
        String current = node.parentId();
        while (current != null && seen.add(current)) {
            if (resolved.containsKey(current)) {
                return current;
            }
            CanvasNode parent = all.get(current);
            current = parent == null ? null : parent.parentId();
        }
        return null;
    }

    private Optional<ResourceTypeSpec> specFor(CanvasNode node) {
        if (node.type() == null || node.type().isBlank()) {
            return Optional.empty();
        }
        // Canvas types are NODE_TYPE_DATA keys ("EC2 Instance"); also accept a raw
        // Terraform type so a future properties panel can bind directly.
        return catalog.findByCanvasType(node.type())
                .or(() -> catalog.find(node.type()));
    }

    /** The author's label, falling back to the type when they never renamed it. */
    private String resourceName(ResolvedNode node) {
        String label = node.canvas().label();
        if (label == null || label.isBlank() || label.equals(node.canvas().type())) {
            return node.spec().tfType().replaceFirst("^aws_", "");
        }
        return label;
    }

    /**
     * Pull attribute values off the canvas node.
     *
     * <p>
     * Only keys the catalog declares are read, so presentation data can never leak
     * into a resource block. Values absent here fall back to catalog defaults in
     * the emitter, which is what lets a node dropped from the palette with no
     * configuration still generate valid Terraform.
     */
    private Map<String, Object> readAttributes(ResolvedNode node) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        JsonNode data = node.canvas().data();

        for (AttributeSpec attr : node.spec().attributes()) {
            if (PRESENTATION_KEYS.contains(attr.name())) {
                continue;
            }
            JsonNode value = data.get(attr.name());
            if (value == null || value.isNull()) {
                continue;
            }
            Object converted = convert(value, attr.kind());
            if (converted != null) {
                attributes.put(attr.name(), converted);
            }
        }

        // A Name tag is the one thing worth inferring: every canvas node has a
        // label, and an untagged resource is a real operational annoyance.
        if (node.spec().attribute("tags").isPresent() && !attributes.containsKey("tags")) {
            String label = node.canvas().label();
            if (label != null && !label.isBlank()) {
                attributes.put("tags", new LinkedHashMap<>(Map.of("Name", label)));
            }
        }
        return attributes;
    }

    private Object convert(JsonNode value, AttributeKind kind) {
        return switch (kind) {
            case BOOL -> value.isBoolean() ? value.asBoolean() : Boolean.parseBoolean(value.asText());
            case NUMBER -> value.isNumber() ? value.numberValue() : parseNumber(value.asText());
            case TAGS -> {
                if (!value.isObject()) {
                    yield null;
                }
                Map<String, Object> tags = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    tags.put(entry.getKey(), entry.getValue().asText());
                }
                yield tags.isEmpty() ? null : tags;
            }
            case BLOCK_LIST -> {
                if (!value.isArray()) {
                    yield null;
                }
                List<Object> blocks = new ArrayList<>();
                for (JsonNode entry : value) {
                    if (!entry.isObject()) {
                        continue;
                    }
                    Map<String, Object> block = new LinkedHashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> fields = entry.fields();
                    while (fields.hasNext()) {
                        var field = fields.next();
                        JsonNode fieldValue = field.getValue();
                        block.put(field.getKey(), fieldValue.isNumber()
                                ? fieldValue.numberValue()
                                : fieldValue.isBoolean() ? fieldValue.asBoolean() : fieldValue.asText());
                    }
                    if (!block.isEmpty()) {
                        blocks.add(block);
                    }
                }
                yield blocks.isEmpty() ? null : blocks;
            }
            default -> {
                String text = value.isValueNode() ? value.asText() : value.toString();
                yield text.isBlank() ? null : text;
            }
        };
    }

    private static Number parseNumber(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            try {
                return Double.valueOf(raw);
            } catch (NumberFormatException e2) {
                return null;
            }
        }
    }

    /**
     * Accept both shapes the canvas can produce.
     *
     * <p>
     * {@code cy.elements().jsons()} yields a flat array, but older blueprints were
     * saved as {@code {nodes: [...], edges: [...]}} — {@code useCloudMap.ts} still
     * handles that legacy form on load, so this has to as well.
     */
    private List<JsonNode> flattenElements(JsonNode root) {
        List<JsonNode> elements = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(elements::add);
            return elements;
        }
        root.path("nodes").forEach(elements::add);
        root.path("edges").forEach(elements::add);
        return elements;
    }

    private static boolean isEdge(JsonNode data) {
        return data.hasNonNull("source") && data.hasNonNull("target");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private record CanvasNode(String id, String label, String type, String parentId, JsonNode data) {
    }

    private record ResolvedNode(CanvasNode canvas, ResourceTypeSpec spec) {
    }

    /**
     * A canvas element that will not appear in the Terraform, and why.
     *
     * <p>
     * Surfaced rather than swallowed: the point is for the UI to be able to say
     * which shapes are covered, so a user is never left wondering why part of their
     * diagram is missing from the output.
     */
    public record Skipped(String nodeId, String label, String canvasType, String reason) {
    }

    public record Result(DesignModel design, List<Skipped> skipped, List<String> notes) {

        public Result {
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public int resourceCount() {
            return design.nodes().size();
        }
    }
}
