package com.xammer.cloud.design.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Loads and self-checks the resource catalog.
 *
 * <p>
 * Entries are JSON on the classpath under {@code design/catalog/}, so adding a
 * resource type is adding a file. The emitter contains no per-type branching,
 * which is the property that makes going from four types to forty tractable.
 *
 * <p>
 * Consistency is verified once at startup and startup <em>fails</em> if the
 * catalog is wrong. That is on purpose. A reference pointing at a type nobody
 * defined, or a required attribute with no default, does not fail here — it fails
 * much later, as Terraform that will not plan, in front of a customer. A missing
 * file is cheap to fix at boot and expensive to diagnose from malformed HCL.
 */
@Component
public class ResourceCatalog {

    private static final Logger logger = LoggerFactory.getLogger(ResourceCatalog.class);

    private static final String CATALOG_PATTERN = "classpath*:design/catalog/*.json";

    private final ObjectMapper objectMapper;

    /** Sorted so iteration order — and therefore emitted output — is stable. */
    private final Map<String, ResourceTypeSpec> byTfType = new TreeMap<>();
    private final Map<String, ResourceTypeSpec> byCanvasType = new LinkedHashMap<>();

    public ResourceCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() {
        List<ResourceTypeSpec> specs = new ArrayList<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources(CATALOG_PATTERN);
            for (Resource file : files) {
                try (var in = file.getInputStream()) {
                    specs.add(objectMapper.readValue(in, ResourceTypeSpec.class));
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Unreadable design catalog entry: " + file.getFilename(), e);
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to scan design catalog at " + CATALOG_PATTERN, e);
        }

        for (ResourceTypeSpec spec : specs) {
            if (spec.tfType() == null || spec.tfType().isBlank()) {
                throw new IllegalStateException("Design catalog entry with no tfType");
            }
            ResourceTypeSpec clash = byTfType.put(spec.tfType(), spec);
            if (clash != null) {
                throw new IllegalStateException("Duplicate design catalog entry for " + spec.tfType());
            }
            if (spec.canvasType() != null && !spec.canvasType().isBlank()) {
                byCanvasType.put(spec.canvasType(), spec);
            }
        }

        verifyInternalConsistency();
        logger.info("Design catalog loaded: {} resource types ({})", byTfType.size(), byTfType.keySet());
    }

    /**
     * Catch the mistakes that would otherwise surface as broken Terraform.
     */
    private void verifyInternalConsistency() {
        List<String> problems = new ArrayList<>();

        for (ResourceTypeSpec spec : byTfType.values()) {
            for (var ref : spec.references()) {
                if (ref.field() == null || ref.field().isBlank()) {
                    problems.add(spec.tfType() + ": reference with no field");
                }
                // A dangling target means the emitter would have nothing to point at.
                if (!byTfType.containsKey(ref.targetTfType())) {
                    problems.add("%s.%s targets unknown type '%s'"
                            .formatted(spec.tfType(), ref.field(), ref.targetTfType()));
                }
            }

            for (var attr : spec.attributes()) {
                if (attr.name() == null || attr.name().isBlank()) {
                    problems.add(spec.tfType() + ": attribute with no name");
                    continue;
                }
                if (attr.kind() == ResourceTypeSpec.AttributeKind.ENUM && attr.options().isEmpty()) {
                    problems.add("%s.%s is an ENUM with no options"
                            .formatted(spec.tfType(), attr.name()));
                }
                if (attr.kind() == ResourceTypeSpec.AttributeKind.BLOCK_LIST && attr.nested().isEmpty()) {
                    problems.add("%s.%s is a BLOCK_LIST with no nested attributes"
                            .formatted(spec.tfType(), attr.name()));
                }
                // Promotion emits `variable "x" { default = ... }`; without a default
                // the variable would be required input and every plan would prompt.
                if (attr.promoteToVariable() && !attr.hasDefault()) {
                    problems.add("%s.%s is promoted to a variable but has no default"
                            .formatted(spec.tfType(), attr.name()));
                }
                // An attribute and a reference writing the same HCL field would emit
                // it twice, which Terraform rejects.
                if (spec.reference(attr.name()).isPresent()) {
                    problems.add("%s.%s is declared as both an attribute and a reference"
                            .formatted(spec.tfType(), attr.name()));
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Design catalog is inconsistent:\n  - " + String.join("\n  - ", problems));
        }
    }

    public Optional<ResourceTypeSpec> find(String tfType) {
        return Optional.ofNullable(byTfType.get(tfType));
    }

    public ResourceTypeSpec require(String tfType) {
        return find(tfType).orElseThrow(() -> new IllegalArgumentException(
                "No design catalog entry for Terraform type '" + tfType + "'"));
    }

    /** Lets the canvas map its existing NODE_TYPE_DATA keys onto Terraform types. */
    public Optional<ResourceTypeSpec> findByCanvasType(String canvasType) {
        return Optional.ofNullable(byCanvasType.get(canvasType));
    }

    /** All types, in stable tfType order. */
    public Collection<ResourceTypeSpec> all() {
        return byTfType.values();
    }

    public int size() {
        return byTfType.size();
    }
}
