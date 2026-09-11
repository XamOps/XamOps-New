package com.xammer.cloud.design.hcl;

import com.xammer.cloud.design.catalog.ResourceCatalog;
import com.xammer.cloud.design.catalog.ResourceTypeSpec;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.AttributeKind;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.AttributeSpec;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.ReferenceSpec;
import com.xammer.cloud.design.model.DesignModel;
import com.xammer.cloud.design.model.DesignModel.ResourceNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link DesignModel} into Terraform.
 *
 * <p>
 * Deterministic by construction: no LLM, no template engine, no third-party HCL
 * library. The same design always produces byte-identical output, which is the
 * property that makes the result reviewable — if generation varied run to run, a
 * pull request would show churn in resources nobody touched, and nobody could
 * read the diff.
 *
 * <p>
 * The substance is reference resolution. Everything else is formatting.
 * {@code subnet_id = "subnet-abc123"} is a hardcoded string that only works in
 * the account it was copied from; {@code subnet_id = aws_subnet.private_1a.id} is
 * infrastructure code Terraform can order and recreate anywhere. Producing the
 * second requires understanding the graph, and that is precisely what
 * {@code ScriptCustomizationService} — regex replacement of {@code &#123;&#123;TOKEN&#125;&#125;} in
 * pre-authored text — cannot do.
 *
 * <p>
 * Contains no per-resource-type branching. Everything type-specific lives in the
 * catalog JSON.
 */
@Service
public class HclEmitter {

    public static final String FILE_PROVIDERS = "providers.tf";
    public static final String FILE_VARIABLES = "variables.tf";
    public static final String FILE_MAIN = "main.tf";
    public static final String FILE_OUTPUTS = "outputs.tf";

    private static final String AWS_PROVIDER_VERSION = "~> 5.0";
    private static final String TERRAFORM_MIN_VERSION = ">= 1.6";

    private final ResourceCatalog catalog;

    public HclEmitter(ResourceCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Emit a complete Terraform module.
     *
     * @return filename to file contents, in the order a reader would open them
     */
    public Map<String, String> emit(DesignModel design) {
        List<ResourceNode> ordered = stableOrder(design);
        Map<String, String> names = assignIdentifiers(ordered);
        List<PromotedVariable> variables = collectVariables(ordered, names);

        Map<String, String> files = new LinkedHashMap<>();
        files.put(FILE_PROVIDERS, emitProviders());
        files.put(FILE_VARIABLES, emitVariables(design, variables));
        files.put(FILE_MAIN, emitMain(design, ordered, names));
        String outputs = emitOutputs(ordered, names);
        if (!outputs.isBlank()) {
            files.put(FILE_OUTPUTS, outputs);
        }
        return files;
    }

    /** Convenience for the common "show me the code" case. */
    public String emitSingleFile(DesignModel design) {
        StringBuilder sb = new StringBuilder();
        emit(design).forEach((file, content) -> sb
                .append("# ").append("─".repeat(72)).append('\n')
                .append("# ").append(file).append('\n')
                .append("# ").append("─".repeat(72)).append('\n')
                .append(content).append('\n'));
        return sb.toString().stripTrailing() + "\n";
    }

    // ─── Ordering and naming ─────────────────────────────────────────────────

    /**
     * Dependency order, falling back to type then name.
     *
     * <p>
     * Terraform builds its own dependency graph, so this is not required for
     * correctness — it is so the file reads the way a person would write it, VPC
     * before subnet before instance. The tie-break on (tfType, name) is what makes
     * output stable regardless of the order the canvas happened to hand us nodes.
     */
    private List<ResourceNode> stableOrder(DesignModel design) {
        List<ResourceNode> byName = new ArrayList<>(design.nodes());
        byName.sort(Comparator
                .comparing(ResourceNode::tfType)
                .thenComparing(ResourceNode::name, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ResourceNode::id));

        Map<String, ResourceNode> byId = new LinkedHashMap<>();
        byName.forEach(n -> byId.put(n.id(), n));

        List<ResourceNode> sorted = new ArrayList<>(byName.size());
        Set<String> placed = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (ResourceNode node : byName) {
            visit(node, byId, placed, visiting, sorted);
        }
        return sorted;
    }

    private void visit(ResourceNode node, Map<String, ResourceNode> byId,
                       Set<String> placed, Set<String> visiting, List<ResourceNode> sorted) {
        if (node == null || placed.contains(node.id())) {
            return;
        }
        // A cycle is a malformed design. Terraform would reject it too, but bailing
        // here keeps emission from recursing forever; the validator reports it.
        if (!visiting.add(node.id())) {
            return;
        }
        node.references().values().stream()
                .flatMap(List::stream)
                .sorted()
                .forEach(targetId -> visit(byId.get(targetId), byId, placed, visiting, sorted));
        visiting.remove(node.id());
        if (placed.add(node.id())) {
            sorted.add(node);
        }
    }

    /**
     * Map node ids to unique, legal Terraform identifiers.
     *
     * <p>
     * Two cards both called "web tier" both sanitise to {@code web_tier}, and
     * duplicate resource addresses are a hard Terraform error. Collisions get a
     * numeric suffix, assigned in the stable order above so the same design always
     * produces the same names.
     */
    private Map<String, String> assignIdentifiers(List<ResourceNode> ordered) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Integer> usedPerType = new HashMap<>();
        for (ResourceNode node : ordered) {
            String base = HclWriter.identifier(
                    node.name() == null || node.name().isBlank() ? node.tfType() : node.name());
            String key = node.tfType() + "." + base;
            int seen = usedPerType.merge(key, 1, Integer::sum);
            names.put(node.id(), seen == 1 ? base : base + "_" + seen);
        }
        return names;
    }

    // ─── providers.tf ────────────────────────────────────────────────────────

    private String emitProviders() {
        HclWriter w = new HclWriter();
        w.comment("Generated by XamOps CloudMap. Edit the design, not this file.")
                .blankLine()
                .openBlock("terraform")
                .attribute("required_version", TERRAFORM_MIN_VERSION)
                .blankLine()
                .openNested("required_providers")
                .rawAttribute("aws", "{\n      source  = \"hashicorp/aws\"\n      version = \"" + AWS_PROVIDER_VERSION + "\"\n    }")
                .closeBlock()
                .closeBlock()
                .blankLine()
                .openBlock("provider", "aws")
                .rawAttribute("region", "var.region")
                .closeBlock();
        return w.build();
    }

    // ─── variables.tf ────────────────────────────────────────────────────────

    private String emitVariables(DesignModel design, List<PromotedVariable> promoted) {
        HclWriter w = new HclWriter();
        w.openBlock("variable", "region")
                .attribute("description", "AWS region to deploy into.")
                .rawAttribute("type", "string")
                .attribute("default", design.regionOrDefault())
                .closeBlock();

        for (PromotedVariable v : promoted) {
            w.blankLine()
                    .openBlock("variable", v.name());
            if (v.description() != null && !v.description().isBlank()) {
                w.attribute("description", v.description());
            }
            w.rawAttribute("type", v.hclType())
                    .attribute("default", v.defaultValue())
                    .closeBlock();
        }
        return w.build();
    }

    /**
     * Attributes the catalog marks {@code promoteToVariable}.
     *
     * <p>
     * Names are always {@code <resource>_<attribute>} rather than bare
     * {@code <attribute>}. Bare names would be prettier but collide the moment a
     * design has two instances, and resolving that collision by inspecting the
     * whole design would make a variable's name depend on unrelated nodes.
     */
    private List<PromotedVariable> collectVariables(List<ResourceNode> ordered, Map<String, String> names) {
        List<PromotedVariable> variables = new ArrayList<>();
        for (ResourceNode node : ordered) {
            ResourceTypeSpec spec = catalog.find(node.tfType()).orElse(null);
            if (spec == null) {
                continue;
            }
            for (AttributeSpec attr : spec.attributes()) {
                if (!attr.promoteToVariable()) {
                    continue;
                }
                Object value = node.hasAttribute(attr.name()) ? node.attribute(attr.name()) : attr.defaultValue();
                variables.add(new PromotedVariable(
                        names.get(node.id()) + "_" + attr.name(),
                        hclTypeOf(attr.kind()),
                        String.valueOf(value),
                        attr.description()));
            }
        }
        return variables;
    }

    private static String hclTypeOf(AttributeKind kind) {
        return switch (kind) {
            case NUMBER -> "number";
            case BOOL -> "bool";
            case TAGS -> "map(string)";
            default -> "string";
        };
    }

    // ─── main.tf ─────────────────────────────────────────────────────────────

    private String emitMain(DesignModel design, List<ResourceNode> ordered, Map<String, String> names) {
        HclWriter w = new HclWriter();
        if (design.name() != null && !design.name().isBlank()) {
            w.comment(design.name()).blankLine();
        }

        boolean first = true;
        for (ResourceNode node : ordered) {
            ResourceTypeSpec spec = catalog.find(node.tfType()).orElse(null);
            if (spec == null) {
                // Validation reports unknown types; emitting a comment rather than
                // silently dropping the resource keeps the omission visible.
                if (!first) {
                    w.blankLine();
                }
                w.comment("Skipped '" + node.name() + "': no catalog entry for " + node.tfType());
                first = false;
                continue;
            }
            if (!first) {
                w.blankLine();
            }
            first = false;
            emitResource(w, design, node, spec, names);
        }
        return w.build();
    }

    private void emitResource(HclWriter w, DesignModel design, ResourceNode node,
                              ResourceTypeSpec spec, Map<String, String> names) {
        w.openBlock("resource", spec.tfType(), names.get(node.id()));

        // References first: what a resource is attached to matters more to a reader
        // than how it is configured.
        for (ReferenceSpec ref : spec.references()) {
            List<String> targets = node.reference(ref.field());
            if (targets.isEmpty()) {
                continue;
            }
            List<String> expressions = targets.stream()
                    .map(targetId -> referenceExpression(design, targetId, names))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (expressions.isEmpty()) {
                continue;
            }
            if (ref.list()) {
                w.rawAttribute(ref.field(), "[" + String.join(", ", expressions) + "]");
            } else {
                w.rawAttribute(ref.field(), expressions.get(0));
            }
        }

        for (AttributeSpec attr : spec.attributes()) {
            emitAttribute(w, node, attr, names);
        }

        w.closeBlock();
    }

    private void emitAttribute(HclWriter w, ResourceNode node, AttributeSpec attr, Map<String, String> names) {
        if (attr.kind() == AttributeKind.BLOCK_LIST) {
            emitBlockList(w, node, attr);
            return;
        }

        if (attr.promoteToVariable()) {
            w.rawAttribute(attr.name(), "var." + names.get(node.id()) + "_" + attr.name());
            return;
        }

        if (attr.kind() == AttributeKind.TAGS) {
            Object raw = node.attribute(attr.name());
            if (raw instanceof Map<?, ?> map && !map.isEmpty()) {
                Map<String, Object> tags = new LinkedHashMap<>();
                map.forEach((k, v) -> tags.put(String.valueOf(k), v));
                w.mapAttribute(attr.name(), tags);
            }
            return;
        }

        Object value = node.hasAttribute(attr.name())
                ? node.attribute(attr.name())
                : (attr.hasDefault() ? coerceDefault(attr) : null);
        if (value == null) {
            return;
        }
        w.attribute(attr.name(), value);
    }

    private void emitBlockList(HclWriter w, ResourceNode node, AttributeSpec attr) {
        Object raw = node.attribute(attr.name());
        if (!(raw instanceof List<?> entries) || entries.isEmpty()) {
            return;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> fields)) {
                continue;
            }
            w.openNested(attr.name());
            // Nested field order comes from the catalog, not the incoming map, so
            // two designs with the same rules emit identical text.
            for (AttributeSpec nested : attr.nested()) {
                Object value = fields.get(nested.name());
                if (value == null && nested.hasDefault()) {
                    value = coerceDefault(nested);
                }
                if (value == null || (value instanceof String s && s.isBlank())) {
                    continue;
                }
                if (nested.name().endsWith("_blocks") && value instanceof String s) {
                    // cidr_blocks is a list in HCL; accept a comma-separated string
                    // from the UI and split it so authors need not think in lists.
                    w.attribute(nested.name(), List.of(s.split("\\s*,\\s*")));
                } else {
                    w.attribute(nested.name(), value);
                }
            }
            w.closeBlock();
        }
    }

    /** Catalog defaults are strings so the JSON stays readable; convert per kind. */
    private Object coerceDefault(AttributeSpec attr) {
        String raw = attr.defaultValue();
        return switch (attr.kind()) {
            case BOOL -> Boolean.parseBoolean(raw);
            case NUMBER -> {
                try {
                    yield Long.valueOf(raw);
                } catch (NumberFormatException e) {
                    yield Double.valueOf(raw);
                }
            }
            default -> raw;
        };
    }

    /**
     * The expression one resource uses to point at another: the whole point of the
     * exercise. Returns null for a dangling target so a broken edge degrades to an
     * omitted attribute rather than uncompilable HCL; the validator reports it.
     */
    private String referenceExpression(DesignModel design, String targetNodeId, Map<String, String> names) {
        ResourceNode target = design.node(targetNodeId).orElse(null);
        if (target == null) {
            return null;
        }
        String identifier = names.get(target.id());
        if (identifier == null) {
            return null;
        }
        String idAttribute = catalog.find(target.tfType())
                .map(ResourceTypeSpec::idAttributeOrDefault)
                .orElse("id");
        return "%s.%s.%s".formatted(target.tfType(), identifier, idAttribute);
    }

    // ─── outputs.tf ──────────────────────────────────────────────────────────

    private String emitOutputs(List<ResourceNode> ordered, Map<String, String> names) {
        HclWriter w = new HclWriter();
        boolean any = false;
        for (ResourceNode node : ordered) {
            ResourceTypeSpec spec = catalog.find(node.tfType()).orElse(null);
            if (spec == null || spec.outputs().isEmpty()) {
                continue;
            }
            String identifier = names.get(node.id());
            for (String output : spec.outputs()) {
                if (any) {
                    w.blankLine();
                }
                any = true;
                w.openBlock("output", identifier + "_" + output)
                        .rawAttribute("value", "%s.%s.%s".formatted(spec.tfType(), identifier, output))
                        .closeBlock();
            }
        }
        return any ? w.build() : "";
    }

    // ─────────────────────────────────────────────────────────────────────────

    private record PromotedVariable(String name, String hclType, String defaultValue, String description) {
    }
}
