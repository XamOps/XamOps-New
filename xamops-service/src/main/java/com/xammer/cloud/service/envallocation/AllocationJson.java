package com.xammer.cloud.service.envallocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.envallocation.AllocationModel.EnvironmentSpec;
import com.xammer.cloud.service.envallocation.AllocationModel.RuleSpec;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads and writes the stored rule and environment documents.
 *
 * <p>Its own mapper rather than the application's, so a global Jackson setting changed for some
 * unrelated endpoint cannot change how a published version reads back. Unknown fields are
 * ignored, which is what lets a newer build add a field without breaking older versions.
 */
@Component
public class AllocationJson {

    private static final TypeReference<List<RuleSpec>> RULES = new TypeReference<>() {
    };
    private static final TypeReference<List<EnvironmentSpec>> ENVIRONMENTS = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public List<RuleSpec> readRules(String json) {
        return read(json, RULES);
    }

    public List<EnvironmentSpec> readEnvironments(String json) {
        return read(json, ENVIRONMENTS);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise allocation document", e);
        }
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<T> value = mapper.readValue(json, type);
            return value == null ? List.of() : value;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored allocation document is unreadable", e);
        }
    }
}
