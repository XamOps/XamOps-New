package com.xammer.cloud.service.stack;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The parameters a CloudFormation template declares, read from the template itself.
 *
 * <p>Exists so a stack update can be built against the template it is moving <em>to</em>
 * rather than the one it is moving from. Passing the old stack's parameter list to a newer
 * template fails CreateChangeSet two ways: a parameter the new template dropped is rejected
 * as not existing, and one it added without a default is rejected as missing.
 *
 * <p>Parsed locally rather than through {@code cloudformation:GetTemplateSummary}, because the
 * connection role in customers' accounts does not grant it. Depending on it would mean an
 * account needs a stack update before it can receive stack updates.
 */
final class TemplateParameters {

    private TemplateParameters() {
    }

    /**
     * Declared parameter names, mapped to whether each carries a default.
     *
     * <p>A parameter with a default can be left out of an update and CloudFormation fills it
     * in; one without must be supplied. An empty-string default still counts as a default.
     *
     * @throws IllegalArgumentException when the body is not a template this can read
     */
    static Map<String, Boolean> parse(String templateBody) {
        Object root;
        try {
            root = new Yaml(new CloudFormationConstructor()).load(templateBody);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Template is not readable YAML or JSON: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> top)) {
            throw new IllegalArgumentException("Template has no top-level mapping.");
        }

        Map<String, Boolean> declared = new LinkedHashMap<>();
        if (top.get("Parameters") instanceof Map<?, ?> parameters) {
            for (Map.Entry<?, ?> entry : parameters.entrySet()) {
                boolean hasDefault = entry.getValue() instanceof Map<?, ?> spec && spec.containsKey("Default");
                declared.put(String.valueOf(entry.getKey()), hasDefault);
            }
        }
        return declared;
    }

    /**
     * Reads a template without resolving CloudFormation's short-form functions.
     *
     * <p>{@code !Ref}, {@code !Sub}, {@code !GetAtt} and the rest are local YAML tags that a
     * plain YAML loader rejects outright. Their values are irrelevant here — only the
     * Parameters block matters, and CloudFormation forbids functions inside it — so every
     * {@code !}-prefixed tag is read as nothing rather than failing the whole parse.
     */
    private static final class CloudFormationConstructor extends SafeConstructor {
        CloudFormationConstructor() {
            super(new LoaderOptions());
            this.yamlMultiConstructors.put("!", new AbstractConstruct() {
                @Override
                public Object construct(Node node) {
                    return null;
                }
            });
        }
    }
}
