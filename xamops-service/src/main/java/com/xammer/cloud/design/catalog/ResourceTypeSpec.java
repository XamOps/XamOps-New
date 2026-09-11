package com.xammer.cloud.design.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/**
 * What one resource type on the design canvas means in Terraform.
 *
 * <p>
 * This is the layer the CloudMap canvas has always been missing. A saved design
 * used to be {@code JSON.stringify(cy.elements().jsons())} — an orange box at
 * (340, 120) captioned "web-tier". That records a picture. It does not record
 * that the box is an {@code aws_instance}, nor that dragging it inside another
 * box means {@code subnet_id}, so no Terraform could ever be derived from it.
 *
 * <p>
 * A spec is <em>data</em>, loaded from {@code design/catalog/*.json}, not code.
 * That is deliberate: the emitter must contain no per-resource-type branching, so
 * widening coverage from four types to forty is a matter of adding files rather
 * than editing the generator. Note that the 32 hand-written Terraform templates
 * already shipped in {@code data/seed/terraform_scripts_seed.sql} are the source
 * material for these entries — they encode how each resource should be written.
 *
 * @param tfType       Terraform resource type, e.g. {@code aws_subnet}. Also the
 *                     first segment of any reference to this resource.
 * @param displayName  Human label for the palette and properties panel.
 * @param canvasType   The existing {@code NODE_TYPE_DATA} key in
 *                     {@code frontend-app/src/types/cloudmap.ts}, so the catalog
 *                     lines up with the icons and colours the canvas already has.
 * @param idAttribute  Attribute other resources reference, almost always
 *                     {@code id}. This is what turns a canvas edge into
 *                     {@code aws_vpc.main.id}.
 * @param attributes   Configurable fields, in the order they should be emitted
 *                     and rendered. Order is part of the contract: stable output
 *                     is what makes generated code reviewable in a diff.
 * @param references   Links to other resources, and what each one means in HCL.
 * @param outputs      Attributes worth surfacing in {@code outputs.tf}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResourceTypeSpec(
        String tfType,
        String displayName,
        String canvasType,
        String idAttribute,
        List<AttributeSpec> attributes,
        List<ReferenceSpec> references,
        List<String> outputs) {

    public ResourceTypeSpec {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        references = references == null ? List.of() : List.copyOf(references);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }

    /** The attribute used when another resource references this one. */
    public String idAttributeOrDefault() {
        return idAttribute == null || idAttribute.isBlank() ? "id" : idAttribute;
    }

    public Optional<AttributeSpec> attribute(String name) {
        return attributes.stream().filter(a -> a.name().equals(name)).findFirst();
    }

    public Optional<ReferenceSpec> reference(String field) {
        return references.stream().filter(r -> r.field().equals(field)).findFirst();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * How a value is written in HCL, and how the properties panel should collect
     * it.
     *
     * <p>
     * The distinction that matters to the emitter is quoting: {@link #NUMBER} and
     * {@link #BOOL} are emitted bare, everything scalar is quoted, {@link #TAGS}
     * and {@link #BLOCK_LIST} expand into braces. The finer kinds
     * ({@link #CIDR}, {@link #AZ}, {@link #INSTANCE_TYPE}) quote identically to a
     * string but let the UI offer a real control and let the validator check the
     * shape.
     */
    public enum AttributeKind {
        STRING,
        NUMBER,
        BOOL,
        /** Quoted like a string; validated as an IPv4 CIDR block. */
        CIDR,
        /** Availability zone, e.g. {@code ap-south-1a}. */
        AZ,
        /** EC2 instance type, e.g. {@code t3.large}. */
        INSTANCE_TYPE,
        /** Quoted string constrained to {@link AttributeSpec#options()}. */
        ENUM,
        /** {@code key = value} pairs inside braces, as in a {@code tags} block. */
        TAGS,
        /** Repeated named blocks, as in {@code ingress { ... }} on a security group. */
        BLOCK_LIST
    }

    /**
     * One configurable field on a resource.
     *
     * @param name              HCL attribute name, emitted verbatim.
     * @param kind              How to write and validate it.
     * @param required          When true, a design missing it fails validation
     *                          rather than producing Terraform that fails at plan.
     * @param defaultValue      Used when the design omits the field. Kept as a
     *                          string and converted per {@code kind}, so catalog
     *                          JSON stays readable.
     * @param description       Help text for the properties panel, and the
     *                          {@code description} of a promoted variable.
     * @param options           Allowed values for {@link AttributeKind#ENUM}.
     * @param promoteToVariable When true the value becomes a {@code variable}
     *                          block and the attribute references it. This is what
     *                          makes output idiomatic — a region or instance type
     *                          belongs in {@code variables.tf}, not inlined at the
     *                          call site — and it stays catalog-driven rather than
     *                          special-cased per type in the emitter.
     * @param nested            Field specs for {@link AttributeKind#BLOCK_LIST}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AttributeSpec(
            String name,
            AttributeKind kind,
            boolean required,
            String defaultValue,
            String description,
            List<String> options,
            boolean promoteToVariable,
            List<AttributeSpec> nested) {

        public AttributeSpec {
            kind = kind == null ? AttributeKind.STRING : kind;
            options = options == null ? List.of() : List.copyOf(options);
            nested = nested == null ? List.of() : List.copyOf(nested);
        }

        public boolean hasDefault() {
            return defaultValue != null && !defaultValue.isBlank();
        }
    }

    /** Where a reference comes from on the canvas. */
    public enum ReferenceVia {
        /**
         * Nesting. Dropping an EC2 card inside a subnet card is what produces
         * {@code subnet_id = aws_subnet.private_1a.id}; the existing
         * {@code cytoscape-compound-drag-and-drop} interaction already captures it.
         */
        CONTAINMENT,
        /**
         * An explicit connection drawn between two cards, which is how a security
         * group attachment is expressed.
         */
        EDGE
    }

    /**
     * A link from this resource to another, and the HCL it becomes.
     *
     * <p>
     * References are the whole reason this catalog exists. Emitting
     * {@code subnet_id = "subnet-abc123"} would be a hardcoded string that only
     * works in the account it was copied from. Emitting
     * {@code subnet_id = aws_subnet.private_1a.id} is real infrastructure code
     * that Terraform can order, plan and recreate anywhere — and it can only be
     * produced by something that understands the graph.
     *
     * @param field        HCL attribute the reference is written to.
     * @param targetTfType Resource type this may point at.
     * @param via          Canvas gesture that creates it.
     * @param required     When true, a design without it fails validation.
     * @param list         When true the field takes a list, so a single target
     *                     still emits {@code [aws_security_group.web.id]}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReferenceSpec(
            String field,
            String targetTfType,
            ReferenceVia via,
            boolean required,
            boolean list) {

        public ReferenceSpec {
            via = via == null ? ReferenceVia.EDGE : via;
        }
    }
}
