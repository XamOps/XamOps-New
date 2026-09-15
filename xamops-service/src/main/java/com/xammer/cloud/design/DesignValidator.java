package com.xammer.cloud.design;

import com.xammer.cloud.design.catalog.ResourceCatalog;
import com.xammer.cloud.design.catalog.ResourceTypeSpec;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.AttributeKind;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.AttributeSpec;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.ReferenceSpec;
import com.xammer.cloud.design.hcl.HclWriter;
import com.xammer.cloud.design.model.DesignModel;
import com.xammer.cloud.design.model.DesignModel.ResourceNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a design before it is turned into Terraform.
 *
 * <p>
 * The value here is <em>where</em> a mistake surfaces. Without this, a subnet with
 * no VPC becomes Terraform missing a required argument, and the author finds out
 * from a plan failure — or from an apply that half-succeeds. With it, the canvas
 * can mark the offending card while they are still drawing.
 *
 * <p>
 * Errors block generation; warnings do not. A design still under construction
 * should not be unusable, so anything recoverable is reported as a warning and the
 * emitter degrades gracefully — a dangling reference omits an attribute rather
 * than producing text that will not parse.
 */
@Service
public class DesignValidator {

    /** IPv4 CIDR: four octets and a prefix length, both range-checked below. */
    private static final Pattern CIDR = Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$");

    private final ResourceCatalog catalog;

    public DesignValidator(ResourceCatalog catalog) {
        this.catalog = catalog;
    }

    public Result validate(DesignModel design) {
        List<Finding> findings = new ArrayList<>();

        if (design.nodes().isEmpty()) {
            findings.add(Finding.warning(null, null, "Design is empty."));
            return new Result(findings);
        }

        Set<String> nodeIds = new HashSet<>();
        Map<String, String> takenNames = new HashMap<>();

        for (ResourceNode node : design.nodes()) {
            if (!nodeIds.add(node.id())) {
                findings.add(Finding.error(node.id(), null, "Duplicate node id '" + node.id() + "'."));
            }

            ResourceTypeSpec spec = catalog.find(node.tfType()).orElse(null);
            if (spec == null) {
                findings.add(Finding.error(node.id(), null,
                        "No catalog entry for type '" + node.tfType() + "', so it cannot be generated."));
                continue;
            }

            validateName(node, findings, takenNames);
            validateAttributes(node, spec, findings);
            validateReferences(design, node, spec, findings);
        }

        detectCycles(design, findings);
        return new Result(findings);
    }

    private void validateName(ResourceNode node, List<Finding> findings, Map<String, String> takenNames) {
        if (node.name() == null || node.name().isBlank()) {
            findings.add(Finding.warning(node.id(), null,
                    "No name; one will be derived from the resource type."));
            return;
        }
        String identifier = HclWriter.identifier(node.name());
        String key = node.tfType() + "." + identifier;
        String previous = takenNames.put(key, node.id());
        if (previous != null) {
            // Not an error: the emitter suffixes collisions deterministically. But
            // two resources called the same thing is almost always unintended.
            findings.add(Finding.warning(node.id(), null,
                    "Name resolves to '%s', which another %s already uses; it will be suffixed."
                            .formatted(identifier, node.tfType())));
        }
    }

    private void validateAttributes(ResourceNode node, ResourceTypeSpec spec, List<Finding> findings) {
        for (AttributeSpec attr : spec.attributes()) {
            boolean present = node.hasAttribute(attr.name());
            if (!present && attr.required() && !attr.hasDefault()) {
                findings.add(Finding.error(node.id(), attr.name(),
                        "'%s' is required and has no default.".formatted(attr.name())));
                continue;
            }
            if (!present) {
                continue;
            }

            Object value = node.attribute(attr.name());
            switch (attr.kind()) {
                case CIDR -> {
                    if (!isValidCidr(String.valueOf(value))) {
                        findings.add(Finding.error(node.id(), attr.name(),
                                "'%s' is not a valid IPv4 CIDR block.".formatted(value)));
                    }
                }
                case NUMBER -> {
                    if (!(value instanceof Number) && !isNumeric(String.valueOf(value))) {
                        findings.add(Finding.error(node.id(), attr.name(),
                                "'%s' must be a number.".formatted(attr.name())));
                    }
                }
                case BOOL -> {
                    String s = String.valueOf(value);
                    if (!(value instanceof Boolean) && !s.equals("true") && !s.equals("false")) {
                        findings.add(Finding.error(node.id(), attr.name(),
                                "'%s' must be true or false.".formatted(attr.name())));
                    }
                }
                case ENUM -> {
                    if (!attr.options().contains(String.valueOf(value))) {
                        findings.add(Finding.error(node.id(), attr.name(),
                                "'%s' is not one of %s.".formatted(value, attr.options())));
                    }
                }
                case BLOCK_LIST -> {
                    if (!(value instanceof List<?>)) {
                        findings.add(Finding.error(node.id(), attr.name(),
                                "'%s' must be a list of blocks.".formatted(attr.name())));
                    }
                }
                case TAGS -> {
                    if (!(value instanceof Map<?, ?>)) {
                        findings.add(Finding.error(node.id(), attr.name(),
                                "'%s' must be a map.".formatted(attr.name())));
                    }
                }
                default -> {
                    // STRING, AZ, INSTANCE_TYPE: any scalar is acceptable here.
                }
            }
        }
    }

    private void validateReferences(DesignModel design, ResourceNode node,
                                    ResourceTypeSpec spec, List<Finding> findings) {
        for (ReferenceSpec ref : spec.references()) {
            List<String> targets = node.reference(ref.field());

            if (targets.isEmpty()) {
                if (ref.required()) {
                    findings.add(Finding.error(node.id(), ref.field(),
                            "%s must be connected to %s (%s)."
                                    .formatted(spec.displayName(), ref.targetTfType(),
                                            ref.via() == ResourceTypeSpec.ReferenceVia.CONTAINMENT
                                                    ? "place it inside one"
                                                    : "draw a connection")));
                }
                continue;
            }

            if (!ref.list() && targets.size() > 1) {
                findings.add(Finding.error(node.id(), ref.field(),
                        "'%s' accepts one target but %d are connected.".formatted(ref.field(), targets.size())));
            }

            for (String targetId : targets) {
                ResourceNode target = design.node(targetId).orElse(null);
                if (target == null) {
                    // The emitter omits an attribute it cannot resolve. For an optional
                    // field that is a harmless degradation, but for a required one the
                    // result is Terraform missing a mandatory argument — so it has to
                    // block generation rather than warn.
                    String message = "Connected to a node that is not in this design, "
                            + "so '" + ref.field() + "' cannot be resolved.";
                    findings.add(ref.required()
                            ? Finding.error(node.id(), ref.field(), message)
                            : Finding.warning(node.id(), ref.field(),
                                    message + " The attribute will be omitted."));
                    continue;
                }
                if (!ref.targetTfType().equals(target.tfType())) {
                    findings.add(Finding.error(node.id(), ref.field(),
                            "'%s' must point at %s, but '%s' is %s."
                                    .formatted(ref.field(), ref.targetTfType(),
                                            target.name(), target.tfType())));
                }
            }
        }

        // Anything referenced through a field the catalog does not declare would be
        // silently dropped, so say so rather than emitting incomplete Terraform.
        for (String field : node.references().keySet()) {
            if (spec.reference(field).isEmpty()) {
                findings.add(Finding.warning(node.id(), field,
                        "'%s' is not a known reference on %s and will be ignored."
                                .formatted(field, node.tfType())));
            }
        }
    }

    /**
     * A reference cycle cannot be generated: Terraform rejects it, and a naive walk
     * over it would not terminate.
     */
    private void detectCycles(DesignModel design, List<Finding> findings) {
        Map<String, List<String>> edges = new HashMap<>();
        design.nodes().forEach(n -> edges.put(n.id(),
                n.references().values().stream().flatMap(List::stream).toList()));

        Set<String> done = new HashSet<>();
        Set<String> onPath = new HashSet<>();
        for (String id : edges.keySet()) {
            if (walk(id, edges, done, onPath)) {
                findings.add(Finding.error(id, null,
                        "This resource is part of a circular dependency, which Terraform cannot resolve."));
            }
        }
    }

    private boolean walk(String id, Map<String, List<String>> edges, Set<String> done, Set<String> onPath) {
        if (done.contains(id)) {
            return false;
        }
        if (!onPath.add(id)) {
            return true;
        }
        for (String next : edges.getOrDefault(id, List.of())) {
            if (edges.containsKey(next) && walk(next, edges, done, onPath)) {
                onPath.remove(id);
                return true;
            }
        }
        onPath.remove(id);
        done.add(id);
        return false;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    static boolean isValidCidr(String value) {
        var m = CIDR.matcher(value == null ? "" : value.trim());
        if (!m.matches()) {
            return false;
        }
        for (int i = 1; i <= 4; i++) {
            if (Integer.parseInt(m.group(i)) > 255) {
                return false;
            }
        }
        return Integer.parseInt(m.group(5)) <= 32;
    }

    private static boolean isNumeric(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    public enum Severity { ERROR, WARNING }

    /**
     * @param nodeId node the finding belongs to, so the canvas can mark that card
     * @param field  attribute or reference field, when the finding is specific
     */
    public record Finding(Severity severity, String nodeId, String field, String message) {

        static Finding error(String nodeId, String field, String message) {
            return new Finding(Severity.ERROR, nodeId, field, message);
        }

        static Finding warning(String nodeId, String field, String message) {
            return new Finding(Severity.WARNING, nodeId, field, message);
        }
    }

    public record Result(List<Finding> findings) {

        public Result {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        /** Only errors block generation; warnings are advisory. */
        public boolean canGenerate() {
            return errors().isEmpty();
        }

        public List<Finding> errors() {
            return findings.stream().filter(f -> f.severity() == Severity.ERROR).toList();
        }

        public List<Finding> warnings() {
            return findings.stream().filter(f -> f.severity() == Severity.WARNING).toList();
        }
    }
}
