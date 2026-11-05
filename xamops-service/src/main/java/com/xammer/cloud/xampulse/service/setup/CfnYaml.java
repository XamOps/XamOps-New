package com.xammer.cloud.xampulse.service.setup;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads CloudFormation YAML into plain maps and lists. Short-form tags become the long form as a one-key map:
 * {@code !Ref X} is {@code {"Ref": "X"}}, {@code !Sub s} is {@code {"Fn::Sub": "s"}}, {@code !Condition C} is
 * {@code {"Condition": "C"}}. Used to read the connection-role template for the trust panel and to assert on the
 * XamPulse templates in tests.
 */
public final class CfnYaml {

    private static final List<String> TAGS = List.of("Ref", "Sub", "GetAtt", "If", "Equals", "Not", "Join", "Select", "Split",
            "FindInMap", "Condition", "And", "Or", "Base64", "Cidr", "ImportValue", "GetAZs", "Transform", "ToJsonString", "Length");

    private CfnYaml() {}

    private static final class Constructor extends SafeConstructor {
        Constructor(LoaderOptions options) {
            super(options);
            for (String t : TAGS) {
                yamlConstructors.put(new Tag("!" + t), new AbstractConstruct() {
                    @Override
                    public Object construct(Node node) {
                        Object value;
                        if (node instanceof ScalarNode s) {
                            value = constructScalar(s);
                            if ("GetAtt".equals(t) && value instanceof String str && str.contains(".")) {
                                int dot = str.indexOf('.');
                                value = List.of(str.substring(0, dot), str.substring(dot + 1));
                            }
                        } else if (node instanceof SequenceNode q) {
                            value = constructSequence(q);
                        } else {
                            value = constructMapping((org.yaml.snakeyaml.nodes.MappingNode) node);
                        }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("Ref".equals(t) || "Condition".equals(t) ? t : "Fn::" + t, value);
                        return m;
                    }
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        Object o = new Yaml(new Constructor(options)).load(yaml);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("Template is not a YAML mapping");
        }
        return (Map<String, Object>) o;
    }
}
