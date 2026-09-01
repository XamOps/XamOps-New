package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.SavedCustomization;
import com.xammer.cloud.domain.ScriptParameter;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.devops.CustomizedScript;
import com.xammer.cloud.dto.devops.ScriptParameters;
import com.xammer.cloud.dto.devops.ValidationResult;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import com.xammer.cloud.repository.SavedCustomizationRepository;
import com.xammer.cloud.repository.ScriptParameterRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of the script customization engine.
 *
 * <p>Scripts contain {@code {{PARAMETER_NAME}}} placeholders. This service fetches
 * the parameter definitions for a script, validates user-supplied values against
 * each parameter's constraints, and produces a customized, ready-to-use script by
 * substituting the placeholders. Users can also persist parameter presets for reuse.
 *
 * Requirements: REQ-2.2.1, REQ-2.2.2, REQ-2.2.3, REQ-2.2.4, REQ-2.2.7
 */
@Service
public class ScriptCustomizationService implements IScriptCustomizationService {

    private static final Logger logger = LoggerFactory.getLogger(ScriptCustomizationService.class);

    /** Maximum presets a user may save per script (REQ-2.2.7). */
    static final int MAX_PRESETS_PER_SCRIPT = 10;

    /** Reserved keys that may be supplied within the userInputs map for presets. */
    static final String KEY_CUSTOMIZATION_NAME = "_customizationName";
    static final String KEY_IS_DEFAULT = "_isDefault";

    /** Matches {{PARAMETER_NAME}} placeholders. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private final DevOpsScriptRepository scriptRepository;
    private final ScriptParameterRepository parameterRepository;
    private final SavedCustomizationRepository savedCustomizationRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public ScriptCustomizationService(DevOpsScriptRepository scriptRepository,
                                      ScriptParameterRepository parameterRepository,
                                      SavedCustomizationRepository savedCustomizationRepository,
                                      CloudAccountRepository cloudAccountRepository,
                                      UserRepository userRepository,
                                      ObjectMapper objectMapper) {
        this.scriptRepository = scriptRepository;
        this.parameterRepository = parameterRepository;
        this.savedCustomizationRepository = savedCustomizationRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Parameters
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ScriptParameters getScriptParameters(Long scriptId) {
        DevOpsScript script = requireScript(scriptId);
        List<ScriptParameter> params = parameterRepository.findByScriptIdOrderByDisplayOrderAsc(scriptId);
        return new ScriptParameters(script.getId(), script.getName(), params);
    }

    // ------------------------------------------------------------------
    // Script generation
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public CustomizedScript generateCustomizedScript(Long scriptId, Map<String, Object> userInputs) {
        DevOpsScript script = requireScript(scriptId);
        List<ScriptParameter> params = parameterRepository.findByScriptIdOrderByDisplayOrderAsc(scriptId);

        Map<String, Object> effectiveValues = resolveEffectiveValues(params, userInputs);
        String customizedCode = replacePlaceholders(script.getCode(), effectiveValues);

        return new CustomizedScript(script.getId(), script.getName(), customizedCode,
                effectiveValues, LocalDateTime.now());
    }

    /**
     * Merge user inputs with parameter defaults. User-supplied values win; any
     * parameter the user did not provide falls back to its default value.
     * Reserved preset keys are excluded.
     */
    private Map<String, Object> resolveEffectiveValues(List<ScriptParameter> params,
                                                        Map<String, Object> userInputs) {
        Map<String, Object> values = new HashMap<>();
        for (ScriptParameter param : params) {
            if (param.getDefaultValue() != null) {
                values.put(param.getName(), param.getDefaultValue());
            }
        }
        if (userInputs != null) {
            for (Map.Entry<String, Object> entry : userInputs.entrySet()) {
                if (isReservedKey(entry.getKey())) {
                    continue;
                }
                if (entry.getValue() != null) {
                    values.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return values;
    }

    /**
     * Replace every {{PARAMETER_NAME}} placeholder with the supplied value.
     * Placeholders without a corresponding value are left untouched.
     */
    private String replacePlaceholders(String code, Map<String, Object> values) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(code);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = values.get(name);
            if (value != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(stringify(value)));
            } else {
                // Leave the placeholder as-is when no value is provided.
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String stringify(Object value) {
        if (value instanceof List) {
            return String.join(", ", ((List<?>) value).stream().map(String::valueOf).toArray(String[]::new));
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ValidationResult validateInputs(Long scriptId, Map<String, Object> userInputs) {
        requireScript(scriptId);
        List<ScriptParameter> params = parameterRepository.findByScriptIdOrderByDisplayOrderAsc(scriptId);
        Map<String, Object> inputs = userInputs != null ? userInputs : new HashMap<>();

        ValidationResult result = ValidationResult.valid();
        for (ScriptParameter param : params) {
            validateParameter(param, inputs.get(param.getName()), result);
        }
        return result;
    }

    private void validateParameter(ScriptParameter param, Object value, ValidationResult result) {
        boolean missing = value == null || (value instanceof String && ((String) value).trim().isEmpty());

        if (Boolean.TRUE.equals(param.getRequired()) && missing) {
            result.addError(param.getName(), param.getLabel() + " is required");
            return;
        }
        if (missing) {
            return; // optional and not provided -> nothing to validate
        }

        JsonNode rules = parseValidationRules(param);
        if (rules == null || rules.isNull()) {
            return;
        }

        // Regex pattern validation (text inputs)
        if (rules.hasNonNull("pattern")) {
            String pattern = rules.get("pattern").asText();
            try {
                if (!Pattern.compile(pattern).matcher(String.valueOf(value)).matches()) {
                    result.addError(param.getName(), param.getLabel() + " does not match the required format");
                }
            } catch (Exception e) {
                logger.warn("Invalid regex pattern '{}' for parameter '{}'", pattern, param.getName());
            }
        }

        // Numeric range validation (min/max)
        if (rules.hasNonNull("min") || rules.hasNonNull("max")) {
            try {
                double numeric = Double.parseDouble(String.valueOf(value));
                if (rules.hasNonNull("min") && numeric < rules.get("min").asDouble()) {
                    result.addError(param.getName(),
                            param.getLabel() + " must be at least " + rules.get("min").asText());
                }
                if (rules.hasNonNull("max") && numeric > rules.get("max").asDouble()) {
                    result.addError(param.getName(),
                            param.getLabel() + " must be at most " + rules.get("max").asText());
                }
            } catch (NumberFormatException e) {
                result.addError(param.getName(), param.getLabel() + " must be a number");
            }
        }

        // Options validation (select / multiselect)
        if (rules.has("options") && rules.get("options").isArray() && rules.get("options").size() > 0) {
            List<String> allowed = new ArrayList<>();
            for (JsonNode option : rules.get("options")) {
                if (option.has("value")) {
                    allowed.add(option.get("value").asText());
                } else {
                    allowed.add(option.asText());
                }
            }
            for (Object selected : asList(value)) {
                if (!allowed.contains(String.valueOf(selected))) {
                    result.addError(param.getName(),
                            "'" + selected + "' is not a valid option for " + param.getLabel());
                }
            }
        }
    }

    private List<Object> asList(Object value) {
        List<Object> list = new ArrayList<>();
        if (value instanceof List) {
            list.addAll((List<?>) value);
        } else {
            list.add(value);
        }
        return list;
    }

    private JsonNode parseValidationRules(ScriptParameter param) {
        String raw = param.getValidationRules();
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            logger.warn("Unable to parse validationRules for parameter '{}': {}", param.getName(), e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public SavedCustomization saveCustomization(String userId, Long scriptId, Map<String, Object> userInputs) {
        DevOpsScript script = requireScript(scriptId);
        Map<String, Object> inputs = userInputs != null ? new HashMap<>(userInputs) : new HashMap<>();

        boolean requestedDefault = Boolean.TRUE.equals(toBoolean(inputs.remove(KEY_IS_DEFAULT)));
        String customizationName = resolveCustomizationName(inputs.remove(KEY_CUSTOMIZATION_NAME));

        // Enforce the maximum number of presets per user+script (REQ-2.2.7).
        long existingCount = savedCustomizationRepository.countByUserIdAndScriptId(userId, scriptId);
        if (existingCount >= MAX_PRESETS_PER_SCRIPT) {
            throw new IllegalStateException(
                    "Maximum of " + MAX_PRESETS_PER_SCRIPT + " presets reached for this script");
        }

        String parameterValuesJson = serialize(inputs);
        String generatedCode = generateCustomizedScript(scriptId, inputs).getCustomizedCode();

        SavedCustomization customization =
                new SavedCustomization(userId, script, customizationName, parameterValuesJson, generatedCode);

        if (requestedDefault) {
            clearExistingDefault(userId, scriptId);
            customization.setIsDefault(true);
        }

        return savedCustomizationRepository.save(customization);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SavedCustomization> getUserCustomizations(String userId) {
        return savedCustomizationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /**
     * Ensure no other preset for this user+script remains flagged as default,
     * preserving the "only one isDefault=true per user+script" invariant.
     */
    private void clearExistingDefault(String userId, Long scriptId) {
        savedCustomizationRepository.findByUserIdAndScriptIdAndIsDefaultTrue(userId, scriptId)
                .ifPresent(existing -> {
                    existing.setIsDefault(false);
                    savedCustomizationRepository.save(existing);
                });
    }

    private String resolveCustomizationName(Object rawName) {
        String name = rawName != null ? String.valueOf(rawName).trim() : "";
        if (name.isEmpty()) {
            name = "Preset " + LocalDateTime.now();
        }
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        return name;
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    // ------------------------------------------------------------------
    // Prefill from cloud account
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> prefillFromUserAccount(String userId, Long scriptId) {
        requireScript(scriptId);
        List<ScriptParameter> params = parameterRepository.findByScriptIdOrderByDisplayOrderAsc(scriptId);

        Map<String, Object> prefilled = new HashMap<>();
        List<CloudAccount> accounts = findUserCloudAccounts(userId);

        for (ScriptParameter param : params) {
            // Smart defaults: always seed any configured default value.
            if (param.getDefaultValue() != null) {
                prefilled.put(param.getName(), param.getDefaultValue());
            }

            if (!"cloudAccount".equalsIgnoreCase(param.getPrefillFrom())) {
                continue;
            }

            Object detected = detectFromCloudAccount(param, accounts);
            if (detected != null) {
                prefilled.put(param.getName(), detected);
            }
        }
        return prefilled;
    }

    /**
     * Best-effort detection of a parameter value from the user's connected cloud
     * accounts based on the parameter name / type (e.g. AWS account id, GCP project
     * id, Azure subscription id, region).
     */
    private Object detectFromCloudAccount(ScriptParameter param, List<CloudAccount> accounts) {
        if (accounts.isEmpty()) {
            return null;
        }
        String name = param.getName() == null ? "" : param.getName().toUpperCase();
        String type = param.getParameterType() == null ? "" : param.getParameterType();

        if (name.contains("PROJECT")) {
            return firstNonNull(accounts, CloudAccount::getGcpProjectId);
        }
        if (name.contains("SUBSCRIPTION")) {
            return firstNonNull(accounts, CloudAccount::getAzureSubscriptionId);
        }
        if (name.contains("ACCOUNT")) {
            return firstNonNull(accounts, CloudAccount::getAwsAccountId);
        }
        if ("region".equalsIgnoreCase(type) || name.contains("REGION")) {
            // Region is not stored on the account; fall back to the parameter default.
            return param.getDefaultValue();
        }
        // VPC_ID, SUBNET_ID, etc. require a live cloud API call (out of scope here);
        // leave them for the user to fill in.
        return null;
    }

    private List<CloudAccount> findUserCloudAccounts(String userId) {
        try {
            return userRepository.findByUsername(userId)
                    .map(User::getClient)
                    .map(client -> cloudAccountRepository.findByClientId(client.getId()))
                    .orElseGet(ArrayList::new);
        } catch (Exception e) {
            logger.warn("Unable to resolve cloud accounts for user '{}': {}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private String firstNonNull(List<CloudAccount> accounts,
                                java.util.function.Function<CloudAccount, String> extractor) {
        for (CloudAccount account : accounts) {
            String value = extractor.apply(account);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private DevOpsScript requireScript(Long scriptId) {
        return scriptRepository.findById(scriptId)
                .orElseThrow(() -> new EntityNotFoundException("DevOps script not found: " + scriptId));
    }

    private String serialize(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            logger.error("Failed to serialize parameter values: {}", e.getMessage());
            return "{}";
        }
    }

    private boolean isReservedKey(String key) {
        return KEY_CUSTOMIZATION_NAME.equals(key) || KEY_IS_DEFAULT.equals(key);
    }

    @SuppressWarnings("unused")
    private Map<String, Object> deserialize(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.warn("Failed to deserialize parameter values: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
