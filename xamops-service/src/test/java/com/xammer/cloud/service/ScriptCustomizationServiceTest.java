package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.SavedCustomization;
import com.xammer.cloud.domain.ScriptParameter;
import com.xammer.cloud.dto.devops.CustomizedScript;
import com.xammer.cloud.dto.devops.ScriptParameters;
import com.xammer.cloud.dto.devops.ValidationResult;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import com.xammer.cloud.repository.SavedCustomizationRepository;
import com.xammer.cloud.repository.ScriptParameterRepository;
import com.xammer.cloud.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ScriptCustomizationService}.
 *
 * Requirements: REQ-2.2.1, REQ-2.2.2, REQ-2.2.4, REQ-2.2.7
 */
@ExtendWith(MockitoExtension.class)
class ScriptCustomizationServiceTest {

    @Mock
    private DevOpsScriptRepository scriptRepository;
    @Mock
    private ScriptParameterRepository parameterRepository;
    @Mock
    private SavedCustomizationRepository savedCustomizationRepository;
    @Mock
    private CloudAccountRepository cloudAccountRepository;
    @Mock
    private UserRepository userRepository;

    private ScriptCustomizationService service;

    private DevOpsScript script;

    @BeforeEach
    void setUp() {
        service = new ScriptCustomizationService(scriptRepository, parameterRepository,
                savedCustomizationRepository, cloudAccountRepository, userRepository, new ObjectMapper());

        script = new DevOpsScript();
        script.setId(1L);
        script.setName("EC2 Instance");
        script.setCategory("terraform");
        script.setCode("region = \"{{REGION}}\"\ninstance_type = \"{{INSTANCE_TYPE}}\"");
    }

    private ScriptParameter param(String name, String type, boolean required,
                                  String defaultValue, String validationRules, int order) {
        ScriptParameter p = new ScriptParameter(script, name, name + " label", type, required);
        p.setDefaultValue(defaultValue);
        p.setValidationRules(validationRules);
        p.setDisplayOrder(order);
        return p;
    }

    @Test
    void getScriptParameters_returnsParametersInDisplayOrder() {
        ScriptParameter region = param("REGION", "region", true, "us-east-1", null, 0);
        ScriptParameter type = param("INSTANCE_TYPE", "instanceType", true, "t2.micro", null, 1);
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Arrays.asList(region, type));

        ScriptParameters result = service.getScriptParameters(1L);

        assertEquals(1L, result.getScriptId());
        assertEquals(2, result.getParameters().size());
        assertEquals("REGION", result.getParameters().get(0).getName());
    }

    @Test
    void generateCustomizedScript_replacesAllPlaceholders() {
        ScriptParameter region = param("REGION", "region", true, "us-east-1", null, 0);
        ScriptParameter type = param("INSTANCE_TYPE", "instanceType", true, "t2.micro", null, 1);
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Arrays.asList(region, type));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("REGION", "eu-west-1");
        inputs.put("INSTANCE_TYPE", "t3.large");

        CustomizedScript result = service.generateCustomizedScript(1L, inputs);

        assertTrue(result.getCustomizedCode().contains("region = \"eu-west-1\""));
        assertTrue(result.getCustomizedCode().contains("instance_type = \"t3.large\""));
        assertFalse(result.getCustomizedCode().contains("{{"));
    }

    @Test
    void generateCustomizedScript_usesDefaultWhenInputMissing() {
        ScriptParameter region = param("REGION", "region", true, "us-east-1", null, 0);
        ScriptParameter type = param("INSTANCE_TYPE", "instanceType", true, "t2.micro", null, 1);
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Arrays.asList(region, type));

        CustomizedScript result = service.generateCustomizedScript(1L, new HashMap<>());

        assertTrue(result.getCustomizedCode().contains("region = \"us-east-1\""));
        assertTrue(result.getCustomizedCode().contains("instance_type = \"t2.micro\""));
    }

    @Test
    void validateInputs_flagsMissingRequiredField() {
        ScriptParameter region = param("REGION", "region", true, null, null, 0);
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Collections.singletonList(region));

        ValidationResult result = service.validateInputs(1L, new HashMap<>());

        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertEquals("REGION", result.getErrors().get(0).getParameterName());
    }

    @Test
    void validateInputs_enforcesRegexPattern() {
        ScriptParameter name = param("BUCKET", "text", true, null,
                "{\"pattern\":\"^[a-z0-9-]+$\"}", 0);
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Collections.singletonList(name));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("BUCKET", "Invalid_Bucket!");

        ValidationResult result = service.validateInputs(1L, inputs);

        assertFalse(result.isValid());
    }

    @Test
    void validateInputs_enforcesNumericRange() {
        ScriptParameter count = param("COUNT", "number", true, null,
                "{\"min\":1,\"max\":5}", 0);
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Collections.singletonList(count));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("COUNT", 10);

        ValidationResult result = service.validateInputs(1L, inputs);

        assertFalse(result.isValid());
    }

    @Test
    void validateInputs_enforcesOptions() {
        ScriptParameter env = param("ENV", "select", true, null,
                "{\"options\":[{\"value\":\"dev\"},{\"value\":\"prod\"}]}", 0);
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Collections.singletonList(env));

        Map<String, Object> badInputs = new HashMap<>();
        badInputs.put("ENV", "staging");
        assertFalse(service.validateInputs(1L, badInputs).isValid());

        Map<String, Object> goodInputs = new HashMap<>();
        goodInputs.put("ENV", "prod");
        assertTrue(service.validateInputs(1L, goodInputs).isValid());
    }

    @Test
    void validateInputs_passesWhenAllValid() {
        ScriptParameter region = param("REGION", "region", true, "us-east-1", null, 0);
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Collections.singletonList(region));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("REGION", "us-west-2");

        assertTrue(service.validateInputs(1L, inputs).isValid());
    }

    @Test
    void saveCustomization_persistsPresetWithJsonValues() {
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Collections.emptyList());
        when(savedCustomizationRepository.countByUserIdAndScriptId("user1", 1L)).thenReturn(0L);
        when(savedCustomizationRepository.save(any(SavedCustomization.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("REGION", "us-east-1");

        SavedCustomization saved = service.saveCustomization("user1", 1L, inputs);

        assertEquals("user1", saved.getUserId());
        assertTrue(saved.getParameterValues().contains("REGION"));
        verify(savedCustomizationRepository).save(any(SavedCustomization.class));
    }

    @Test
    void saveCustomization_clearsExistingDefaultWhenNewDefaultRequested() {
        SavedCustomization existingDefault = new SavedCustomization();
        existingDefault.setIsDefault(true);

        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Collections.emptyList());
        when(savedCustomizationRepository.countByUserIdAndScriptId("user1", 1L)).thenReturn(1L);
        when(savedCustomizationRepository.findByUserIdAndScriptIdAndIsDefaultTrue("user1", 1L))
                .thenReturn(Optional.of(existingDefault));
        when(savedCustomizationRepository.save(any(SavedCustomization.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put(ScriptCustomizationService.KEY_IS_DEFAULT, true);
        inputs.put(ScriptCustomizationService.KEY_CUSTOMIZATION_NAME, "My Preset");

        SavedCustomization saved = service.saveCustomization("user1", 1L, inputs);

        assertFalse(existingDefault.getIsDefault(), "previous default should be cleared");
        assertTrue(saved.getIsDefault(), "new preset should be the default");
        assertEquals("My Preset", saved.getCustomizationName());
    }

    @Test
    void saveCustomization_rejectsWhenMaxPresetsReached() {
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(savedCustomizationRepository.countByUserIdAndScriptId("user1", 1L))
                .thenReturn((long) ScriptCustomizationService.MAX_PRESETS_PER_SCRIPT);

        assertThrows(IllegalStateException.class,
                () -> service.saveCustomization("user1", 1L, new HashMap<>()));
    }

    @Test
    void getUserCustomizations_returnsUserPresets() {
        List<SavedCustomization> presets = Arrays.asList(new SavedCustomization(), new SavedCustomization());
        when(savedCustomizationRepository.findByUserIdOrderByUpdatedAtDesc("user1")).thenReturn(presets);

        assertEquals(2, service.getUserCustomizations("user1").size());
    }

    @Test
    void prefillFromUserAccount_returnsDefaultsWhenNoAccounts() {
        ScriptParameter region = param("REGION", "region", true, "us-east-1", null, 0);
        region.setPrefillFrom("cloudAccount");
        when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(1L))
                .thenReturn(Collections.singletonList(region));
        when(userRepository.findByUsername("user1")).thenReturn(Optional.empty());

        Map<String, Object> prefilled = service.prefillFromUserAccount("user1", 1L);

        assertEquals("us-east-1", prefilled.get("REGION"));
    }
}
