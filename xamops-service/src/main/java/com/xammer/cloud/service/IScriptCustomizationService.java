package com.xammer.cloud.service;

import com.xammer.cloud.domain.SavedCustomization;
import com.xammer.cloud.dto.devops.CustomizedScript;
import com.xammer.cloud.dto.devops.ScriptParameters;
import com.xammer.cloud.dto.devops.ValidationResult;

import java.util.List;
import java.util.Map;

/**
 * Service for customizing DevOps scripts: fetching their parameters, validating
 * user inputs, generating ready-to-use scripts by replacing {{PARAMETER_NAME}}
 * placeholders, and persisting reusable parameter presets.
 *
 * Requirements: REQ-2.2.1, REQ-2.2.2, REQ-2.2.3, REQ-2.2.4, REQ-2.2.7
 */
public interface IScriptCustomizationService {

    /** Get customization parameters for a script (ordered by display order). */
    ScriptParameters getScriptParameters(Long scriptId);

    /** Generate a customized script by replacing placeholders with user inputs. */
    CustomizedScript generateCustomizedScript(Long scriptId, Map<String, Object> userInputs);

    /** Validate user inputs against each parameter's constraints. */
    ValidationResult validateInputs(Long scriptId, Map<String, Object> userInputs);

    /** Save a customization preset for a user. */
    SavedCustomization saveCustomization(String userId, Long scriptId, Map<String, Object> userInputs);

    /** Get all customization presets saved by a user. */
    List<SavedCustomization> getUserCustomizations(String userId);

    /** Pre-fill parameter values from the user's connected cloud account. */
    Map<String, Object> prefillFromUserAccount(String userId, Long scriptId);
}
