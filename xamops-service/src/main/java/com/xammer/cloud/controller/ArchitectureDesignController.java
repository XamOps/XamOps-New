package com.xammer.cloud.controller;

import com.xammer.cloud.design.CanvasDesignTranslator;
import com.xammer.cloud.design.DesignValidator;
import com.xammer.cloud.design.catalog.ResourceCatalog;
import com.xammer.cloud.design.catalog.ResourceTypeSpec;
import com.xammer.cloud.design.hcl.HclEmitter;
import com.xammer.cloud.domain.ArchitectureBlueprint;
import com.xammer.cloud.repository.ArchitectureBlueprintRepository;
import com.xammer.cloud.security.ClientUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * CloudMap design blueprints, scoped to the caller's tenant.
 *
 * <p>
 * This controller previously served {@code repository.findAll()} on
 * {@code /list}, read and overwrote by raw id, and deleted by raw id — with no
 * ownership column on the table and a single shared DataSource on every profile.
 * Any authenticated user could enumerate, read, overwrite and destroy every other
 * tenant's saved architecture.
 *
 * <p>
 * Three rules hold now:
 * <ol>
 * <li>every query carries the caller's {@code clientId};</li>
 * <li>a blueprint belonging to another tenant is reported as 404, not 403, so
 * responses cannot be used to probe which ids exist elsewhere;</li>
 * <li>{@code clientId} is stamped server-side and {@code @JsonIgnore}d on the
 * entity, so a request body cannot nominate its own owner.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/xamops/design")
public class ArchitectureDesignController {

    private static final Logger logger = LoggerFactory.getLogger(ArchitectureDesignController.class);

    private static final int MAX_NAME_LENGTH = 255;
    /**
     * Blueprints are raw Cytoscape element JSON and grow with the canvas. 8 MB is
     * far above any real diagram while still bounding what a single row can cost.
     */
    private static final int MAX_GRAPH_DATA_BYTES = 8 * 1024 * 1024;

    private final ArchitectureBlueprintRepository repository;
    private final CanvasDesignTranslator translator;
    private final DesignValidator validator;
    private final HclEmitter emitter;
    private final ResourceCatalog catalog;

    public ArchitectureDesignController(ArchitectureBlueprintRepository repository,
                                        CanvasDesignTranslator translator,
                                        DesignValidator validator,
                                        HclEmitter emitter,
                                        ResourceCatalog catalog) {
        this.repository = repository;
        this.translator = translator;
        this.validator = validator;
        this.emitter = emitter;
        this.catalog = catalog;
    }

    /**
     * The resource catalog, for the properties panel to render from.
     *
     * <p>
     * Served rather than duplicated in TypeScript on purpose. The catalog is the
     * single description of what a resource type has — its attributes, their kinds,
     * defaults and validation, and which references are legal. A second copy in the
     * frontend would drift, and the failure mode of drift here is a form that
     * collects a field the emitter ignores, or omits one it requires.
     *
     * <p>
     * Public within the tenant and safe to cache: this is product metadata, not
     * customer data. Nothing here varies by client.
     */
    @GetMapping("/catalog")
    public ResponseEntity<List<ResourceTypeSpec>> getCatalog(
            @AuthenticationPrincipal ClientUserDetails user) {
        requireClientId(user);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(List.copyOf(catalog.all()));
    }

    @GetMapping("/list")
    public List<ArchitectureBlueprint> getAllBlueprints(@AuthenticationPrincipal ClientUserDetails user) {
        return repository.findByClientIdOrderByUpdatedAtDesc(requireClientId(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ArchitectureBlueprint> getBlueprint(
            @PathVariable Long id,
            @AuthenticationPrincipal ClientUserDetails user) {
        return repository.findByIdAndClientId(id, requireClientId(user))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Create or update a blueprint.
     *
     * <p>
     * An id in the body means "update", and it is resolved through
     * {@code findByIdAndClientId} first — otherwise this endpoint was a
     * cross-tenant overwrite: post someone else's id with your own graph data and
     * their architecture is gone.
     */
    @PostMapping("/save")
    public ResponseEntity<ArchitectureBlueprint> saveBlueprint(
            @RequestBody ArchitectureBlueprint incoming,
            @AuthenticationPrincipal ClientUserDetails user) {

        Long clientId = requireClientId(user);
        String name = incoming.getName() == null ? "" : incoming.getName().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Blueprint name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Blueprint name must be " + MAX_NAME_LENGTH + " characters or fewer");
        }
        String graphData = incoming.getGraphData();
        if (graphData != null && graphData.length() > MAX_GRAPH_DATA_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Diagram is too large to save");
        }

        ArchitectureBlueprint target;
        if (incoming.getId() != null) {
            target = repository.findByIdAndClientId(incoming.getId(), clientId)
                    .orElseThrow(() -> {
                        logger.warn("Blocked cross-tenant blueprint update: client {} attempted to write blueprint {}",
                                clientId, incoming.getId());
                        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Blueprint not found");
                    });
        } else {
            target = new ArchitectureBlueprint();
            target.setClientId(clientId);
            target.setCreatedBy(user.getId());
        }

        // Copy only the fields a client is allowed to set. Never clientId/createdBy.
        target.setName(name);
        target.setDescription(incoming.getDescription());
        target.setGraphData(graphData);

        return ResponseEntity.ok(repository.save(target));
    }

    /**
     * Generate Terraform for a saved design.
     *
     * <p>
     * Additive on purpose. The canvas save format is unchanged, no column was
     * added, and the existing endpoints keep their contracts — this reads the
     * {@code graph_data} already stored and derives code from it. Existing
     * blueprints therefore become generatable with no conversion.
     *
     * <p>
     * Shapes the catalog does not cover are reported in {@code skipped} rather
     * than failing the request. The design canvas is a general diagramming tool
     * and must stay one; Terraform is a bonus for the parts that happen to be
     * real resources.
     */
    @PostMapping("/{id}/terraform")
    public ResponseEntity<GeneratedTerraform> generateTerraform(
            @PathVariable Long id,
            @RequestParam(required = false) String region,
            @AuthenticationPrincipal ClientUserDetails user) {

        ArchitectureBlueprint blueprint = repository
                .findByIdAndClientId(id, requireClientId(user))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Blueprint not found"));

        CanvasDesignTranslator.Result translated;
        try {
            translated = translator.translate(
                    blueprint.getGraphData(),
                    blueprint.getName(),
                    region == null || region.isBlank() ? "us-east-1" : region);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }

        DesignValidator.Result validation = validator.validate(translated.design());

        // Emit only when the design is sound. Returning half-valid Terraform would
        // be worse than returning none: it looks applyable and is not.
        Map<String, String> files = validation.canGenerate()
                ? emitter.emit(translated.design())
                : Map.of();

        return ResponseEntity.ok(new GeneratedTerraform(
                validation.canGenerate(),
                translated.resourceCount(),
                files,
                validation.findings(),
                translated.skipped(),
                translated.notes()));
    }

    /**
     * Generate Terraform for an unsaved canvas.
     *
     * <p>
     * The {@code /{id}/terraform} variant needs a persisted blueprint, which would
     * force someone to save before they could see any code. Generation is a pure
     * function of the graph, so this takes the canvas straight from the request and
     * persists nothing — that is what lets the editor show HCL updating as the
     * design is drawn.
     *
     * <p>
     * Still authenticated: it is not public, and requiring a tenant keeps it
     * consistent with the rest of the controller. There is no id to confuse, so
     * this carries no cross-tenant surface of its own.
     */
    @PostMapping("/terraform/preview")
    public ResponseEntity<GeneratedTerraform> previewTerraform(
            @RequestBody PreviewRequest request,
            @AuthenticationPrincipal ClientUserDetails user) {

        requireClientId(user);

        String graphData = request.graphData();
        if (graphData != null && graphData.length() > MAX_GRAPH_DATA_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Diagram is too large to generate");
        }

        CanvasDesignTranslator.Result translated;
        try {
            translated = translator.translate(
                    graphData,
                    request.name() == null || request.name().isBlank() ? "Untitled design" : request.name(),
                    request.region() == null || request.region().isBlank() ? "us-east-1" : request.region());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }

        DesignValidator.Result validation = validator.validate(translated.design());
        Map<String, String> files = validation.canGenerate()
                ? emitter.emit(translated.design())
                : Map.of();

        return ResponseEntity.ok(new GeneratedTerraform(
                validation.canGenerate(),
                translated.resourceCount(),
                files,
                validation.findings(),
                translated.skipped(),
                translated.notes()));
    }

    /**
     * @param graphData raw Cytoscape element JSON, as the canvas already serialises it
     */
    public record PreviewRequest(String name, String region, String graphData) {
    }

    /**
     * @param generatable    false when errors blocked emission
     * @param resourceCount  canvas shapes that mapped to a Terraform resource
     * @param files          filename to contents; empty when not generatable
     * @param findings       validation errors and warnings, each tied to a node id
     *                       so the canvas can mark the offending card
     * @param skipped        shapes deliberately excluded, with the reason
     * @param notes          decorative connections that carry no Terraform meaning
     */
    public record GeneratedTerraform(
            boolean generatable,
            int resourceCount,
            Map<String, String> files,
            List<DesignValidator.Finding> findings,
            List<CanvasDesignTranslator.Skipped> skipped,
            List<String> notes) {
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBlueprint(
            @PathVariable Long id,
            @AuthenticationPrincipal ClientUserDetails user) {

        Long clientId = requireClientId(user);
        var owned = repository.findByIdAndClientId(id, clientId);
        if (owned.isEmpty()) {
            logger.warn("Blocked cross-tenant blueprint delete: client {} attempted to delete blueprint {}",
                    clientId, id);
            return ResponseEntity.notFound().build();
        }
        repository.delete(owned.get());
        return ResponseEntity.noContent().build();
    }

    /**
     * The tenant every query in this controller is scoped by.
     *
     * <p>
     * Treated as a hard precondition rather than defaulted: a null principal or a
     * principal with no client is a misconfiguration, and quietly continuing would
     * reintroduce exactly the unscoped read this class was fixing.
     */
    private Long requireClientId(ClientUserDetails user) {
        if (user == null || user.getClientId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Authentication with a tenant is required");
        }
        return user.getClientId();
    }
}
