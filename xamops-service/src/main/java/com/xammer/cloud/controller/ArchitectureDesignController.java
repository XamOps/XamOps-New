package com.xammer.cloud.controller;

import com.xammer.cloud.domain.ArchitectureBlueprint;
import com.xammer.cloud.repository.ArchitectureBlueprintRepository;
import com.xammer.cloud.security.ClientUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

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

    public ArchitectureDesignController(ArchitectureBlueprintRepository repository) {
        this.repository = repository;
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
