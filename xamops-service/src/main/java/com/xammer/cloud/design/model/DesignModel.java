package com.xammer.cloud.design.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The semantic half of a design: what the architecture <em>is</em>, with no
 * mention of how it looks.
 *
 * <p>
 * Keeping this separate from the visual layer is a load-bearing decision.
 * {@code architecture_blueprints.graph_data} currently holds raw Cytoscape
 * element JSON, which conflates the two: positions, colours and labels sit in the
 * same objects as the only facts that matter for infrastructure. Conflated, every
 * re-layout dirties the infrastructure and every attribute edit risks moving
 * boxes. Split, the canvas becomes one view over this model and generated
 * Terraform becomes another, and neither can corrupt the other.
 *
 * <p>
 * A design is a value: no JPA annotations, no identity, no lifecycle. It is
 * serialised into a blueprint row alongside its visual counterpart.
 *
 * @param name      Design name, used to derive the Terraform module header.
 * @param region    Target AWS region. Emitted as {@code var.region} so the same
 *                  design can be applied elsewhere without editing resources.
 * @param nodes     The resources. Order here is irrelevant; the emitter sorts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DesignModel(
        String name,
        String region,
        List<ResourceNode> nodes) {

    public DesignModel {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public static DesignModel of(String name, String region, ResourceNode... nodes) {
        return new DesignModel(name, region, List.of(nodes));
    }

    public Optional<ResourceNode> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }

    public String regionOrDefault() {
        return region == null || region.isBlank() ? "us-east-1" : region;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * One resource on the canvas, expressed in Terraform terms.
     *
     * @param id         Stable canvas node id. Referenced by other nodes and by
     *                   the visual layer, and never emitted into HCL.
     * @param tfType     Catalog key, e.g. {@code aws_subnet}.
     * @param name       Author's name for the resource, e.g. {@code private_1a}.
     *                   Sanitised into a legal Terraform identifier at emit time,
     *                   because it originates from user input.
     * @param attributes Values by attribute name. Catalog defaults fill the gaps,
     *                   so a design only records what was actually set.
     * @param references Target node ids by HCL field. Always a list even for
     *                   scalar fields like {@code vpc_id}, which keeps one shape
     *                   for both and lets the catalog decide how to write it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourceNode(
            String id,
            String tfType,
            String name,
            Map<String, Object> attributes,
            Map<String, List<String>> references) {

        public ResourceNode {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            references = references == null ? Map.of() : Map.copyOf(references);
        }

        public Object attribute(String attributeName) {
            return attributes.get(attributeName);
        }

        public boolean hasAttribute(String attributeName) {
            Object value = attributes.get(attributeName);
            return value != null && !(value instanceof String s && s.isBlank());
        }

        public List<String> reference(String field) {
            return references.getOrDefault(field, List.of());
        }

        /** Fluent builder, mainly so tests and fixtures stay readable. */
        public static Builder builder(String id, String tfType, String name) {
            return new Builder(id, tfType, name);
        }

        public static final class Builder {
            private final String id;
            private final String tfType;
            private final String name;
            private final Map<String, Object> attributes = new LinkedHashMap<>();
            private final Map<String, List<String>> references = new LinkedHashMap<>();

            private Builder(String id, String tfType, String name) {
                this.id = id;
                this.tfType = tfType;
                this.name = name;
            }

            public Builder attr(String key, Object value) {
                attributes.put(key, value);
                return this;
            }

            /** A scalar reference, stored in the same list shape as a multi one. */
            public Builder ref(String field, String targetNodeId) {
                references.put(field, List.of(targetNodeId));
                return this;
            }

            public Builder refs(String field, List<String> targetNodeIds) {
                references.put(field, List.copyOf(targetNodeIds));
                return this;
            }

            public ResourceNode build() {
                return new ResourceNode(id, tfType, name, attributes, references);
            }
        }
    }
}
