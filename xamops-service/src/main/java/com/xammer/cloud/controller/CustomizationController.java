package com.xammer.cloud.controller;

import com.xammer.cloud.domain.SavedCustomization;
import com.xammer.cloud.dto.devops.CustomizeScriptRequest;
import com.xammer.cloud.dto.devops.CustomizedScript;
import com.xammer.cloud.dto.devops.SaveCustomizationRequest;
import com.xammer.cloud.dto.devops.SavedCustomizationResponse;
import com.xammer.cloud.dto.devops.ScriptParameters;
import com.xammer.cloud.dto.devops.ValidationResult;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.IScriptCustomizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for the script customization engine: fetching a script's parameters,
 * generating a customized script from user inputs, and saving / listing reusable
 * parameter presets.
 *
 * <p>Endpoints are rate limited to 100 requests/minute per user and validated
 * with Bean Validation. Parameter-value validation is delegated to the service,
 * which returns a {@link ValidationResult} surfaced as a 400 response.</p>
 *
 * Requirements: REQ-2.2.2, REQ-2.2.6, REQ-2.2.7, REQ-2.8.2
 */
@RestController
@Validated
public class CustomizationController {

    /**
     * Reserved input-map keys understood by the customization service for
     * supplying a preset name and default flag alongside parameter values.
     * Kept in sync with {@code ScriptCustomizationService}.
     */
    private static final String RESERVED_KEY_CUSTOMIZATION_NAME = "_customizationName";
    private static final String RESERVED_KEY_IS_DEFAULT = "_isDefault";

    private final IScriptCustomizationService customizationService;

    public CustomizationController(IScriptCustomizationService customizationService) {
        this.customizationService = customizationService;
    }

    /**
     * Get the customizable parameters for a script (ordered by display order).
     * GET /api/scripts/{id}/parameters
     */
    @GetMapping("/api/scripts/{id}/parameters")
    public ResponseEntity<ScriptParameters> getParameters(@PathVariable Long id) {
        return ResponseEntity.ok(customizationService.getScriptParameters(id));
    }

    /**
     * Generate a customized script from the supplied parameter values. Inputs
     * are validated first; on failure a 400 with the {@link ValidationResult}
     * is returned so the client can show inline field errors.
     * POST /api/scripts/{id}/customize
     */
    @PostMapping("/api/scripts/{id}/customize")
    public ResponseEntity<?> customize(@PathVariable Long id,
                                       @Valid @RequestBody CustomizeScriptRequest request) {
        ValidationResult validation = customizationService.validateInputs(id, request.getInputs());
        if (!validation.isValid()) {
            return ResponseEntity.badRequest().body(validation);
        }
        CustomizedScript customized = customizationService.generateCustomizedScript(id, request.getInputs());
        return ResponseEntity.ok(customized);
    }

    /**
     * List the caller's saved customization presets.
     * GET /api/customizations
     */
    @GetMapping("/api/customizations")
    public ResponseEntity<List<SavedCustomizationResponse>> getCustomizations(
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        List<SavedCustomization> presets =
                customizationService.getUserCustomizations(resolveUserId(userDetails));
        List<SavedCustomizationResponse> body = presets.stream()
                .map(SavedCustomizationResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(body);
    }

    /**
     * Save a reusable customization preset for the caller. Inputs are validated
     * against the script's parameter constraints before persisting.
     * POST /api/customizations
     */
    @PostMapping("/api/customizations")
    public ResponseEntity<?> saveCustomization(
            @Valid @RequestBody SaveCustomizationRequest request,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        ValidationResult validation =
                customizationService.validateInputs(request.getScriptId(), request.getInputs());
        if (!validation.isValid()) {
            return ResponseEntity.badRequest().body(validation);
        }

        // The service reads the preset name / default flag from reserved keys in
        // the inputs map (see ScriptCustomizationService.KEY_CUSTOMIZATION_NAME /
        // KEY_IS_DEFAULT). Inject them from the typed request fields.
        Map<String, Object> inputs = new HashMap<>(request.getInputs());
        inputs.put(RESERVED_KEY_CUSTOMIZATION_NAME, request.getCustomizationName());
        inputs.put(RESERVED_KEY_IS_DEFAULT, request.isDefault());

        SavedCustomization saved = customizationService.saveCustomization(
                resolveUserId(userDetails), request.getScriptId(), inputs);
        return ResponseEntity.ok(SavedCustomizationResponse.from(saved));
    }

    private String resolveUserId(ClientUserDetails userDetails) {
        if (userDetails == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication is required for this operation");
        }
        return userDetails.getUsername();
    }
}
