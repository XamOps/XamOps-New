package com.xammer.cloud.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A CloudFormation template read the way the contract tests need it: with the short-form
 * intrinsic functions kept, so {@code !Ref x} reads as {@code {"Ref": x}} and {@code !Sub s} as
 * {@code {"Fn::Sub": s}}.
 *
 * <p>Statements are collected the way IAM sees them on a role: the role's inline policies, the
 * customer managed policies it attaches, and every {@code AWS::IAM::Policy} that names it. A
 * {@code Fn::If} contributes both branches, because either can be deployed, and
 * {@code AWS::NoValue} contributes nothing.
 */
final class CfnTemplate {

    /** Canonical JSON: keys sorted, no whitespace. Used for equality of intrinsics and sizes. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final Map<String, Object> NO_VALUE = Map.of("Ref", "AWS::NoValue");

    private final String name;
    private final Map<String, Object> root;

    private CfnTemplate(String name, Map<String, Object> root) {
        this.name = name;
        this.root = root;
    }

    static CfnTemplate load(Path path) {
        try {
            return new CfnTemplate(path.getFileName().toString(),
                    asMap(parseYaml(Files.readString(path, StandardCharsets.UTF_8))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses YAML text with the intrinsic tags kept. Also used for expected snippets. */
    static Object parseYaml(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(new TagPreservingConstructor(options)).load(text);
    }

    String name() {
        return name;
    }

    Map<String, Object> section(String section) {
        Object value = root.get(section);
        return value == null ? Map.of() : asMap(value);
    }

    Map<String, Object> resource(String logicalId) {
        Object value = section("Resources").get(logicalId);
        if (value == null) {
            throw new AssertionError(name + " has no resource " + logicalId);
        }
        return asMap(value);
    }

    Map<String, Object> properties(String logicalId) {
        return asMap(resource(logicalId).get("Properties"));
    }

    String type(String logicalId) {
        return String.valueOf(resource(logicalId).get("Type"));
    }

    /** Every statement IAM attaches to the role, in template order. */
    List<Statement> roleStatements(String roleLogicalId) {
        Map<String, Object> role = properties(roleLogicalId);
        List<Statement> statements = new ArrayList<>();
        Set<String> seenPolicies = new HashSet<>();

        for (Object policy : flatten(role.get("Policies"))) {
            Map<String, Object> inline = asMap(policy);
            statements.addAll(statementsOf(inline.get("PolicyDocument"), "inline:" + inline.get("PolicyName")));
        }
        for (Object arn : flatten(role.get("ManagedPolicyArns"))) {
            if (arn instanceof Map<?, ?> ref && ref.get("Ref") instanceof String id
                    && section("Resources").containsKey(id)
                    && "AWS::IAM::ManagedPolicy".equals(type(id)) && seenPolicies.add(id)) {
                statements.addAll(statementsOf(properties(id).get("PolicyDocument"), "managed:" + id));
            }
        }
        Map<String, Object> roleRef = Map.of("Ref", roleLogicalId);
        for (String id : section("Resources").keySet()) {
            String type = type(id);
            if (!"AWS::IAM::Policy".equals(type) && !"AWS::IAM::ManagedPolicy".equals(type)) {
                continue;
            }
            Map<String, Object> properties = properties(id);
            if (flatten(properties.get("Roles")).contains(roleRef) && seenPolicies.add(id)) {
                statements.addAll(statementsOf(properties.get("PolicyDocument"), "policy:" + id));
            }
        }
        return statements;
    }

    /** The statements of one policy document, Fn::If expanded. */
    static List<Statement> statementsOf(Object policyDocument, String source) {
        List<Statement> statements = new ArrayList<>();
        if (policyDocument instanceof Map<?, ?> document) {
            for (Object statement : flatten(document.get("Statement"))) {
                statements.add(new Statement(source, asMap(statement)));
            }
        }
        return statements;
    }

    /** True when an Allow statement grants the action on the resource, under any condition. */
    static boolean allows(List<Statement> statements, String action, Object resource) {
        return statements.stream().anyMatch(s -> s.isAllow() && s.matchesAction(action) && s.matchesResource(resource));
    }

    /**
     * True when an Allow statement grants the action on the resource with no condition or with
     * exactly this condition, so the grant is at least as broad as the one being checked.
     */
    static boolean allows(List<Statement> statements, String action, Object resource, Object condition) {
        return statements.stream().anyMatch(s -> s.isAllow() && s.matchesAction(action) && s.matchesResource(resource)
                && (s.condition() == null || canonical(s.condition()).equals(canonical(condition))));
    }

    /** True when any Allow statement grants the action at all, on any resource or condition. */
    static boolean allowsAction(List<Statement> statements, String action) {
        return statements.stream().anyMatch(s -> s.isAllow() && s.matchesAction(action));
    }

    /** Length of the policy as IAM counts it: JSON without whitespace (intrinsics unresolved). */
    static int minifiedLength(Object policyDocument) {
        return canonical(policyDocument).length();
    }

    static String canonical(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A value as a list of what can be deployed: lists are flattened, each Fn::If contributes both
     * branches and AWS::NoValue is dropped.
     */
    static List<Object> flatten(Object value) {
        List<Object> out = new ArrayList<>();
        if (value == null || NO_VALUE.equals(value)) {
            return out;
        }
        if (value instanceof List<?> list) {
            for (Object element : list) {
                out.addAll(flatten(element));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map && map.size() == 1 && map.get("Fn::If") instanceof List<?> args
                && args.size() == 3) {
            out.addAll(flatten(args.get(1)));
            out.addAll(flatten(args.get(2)));
            return out;
        }
        out.add(value);
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new AssertionError("Expected a mapping, got " + value);
        }
        return (Map<String, Object>) value;
    }

    /** IAM-style wildcard match: {@code *} is any run of characters, {@code ?} one character. */
    static boolean glob(String pattern, String value, boolean ignoreCase) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
        return Pattern.compile(regex.toString(), flags | Pattern.DOTALL).matcher(value).matches();
    }

    /** One statement and the policy it came from. */
    record Statement(String source, Map<String, Object> body) {

        String sid() {
            Object sid = body.get("Sid");
            return sid == null ? null : sid.toString();
        }

        boolean isAllow() {
            return "Allow".equals(body.get("Effect")) && !body.containsKey("NotAction")
                    && !body.containsKey("NotResource");
        }

        List<String> actions() {
            return flatten(body.get("Action")).stream().map(String::valueOf).toList();
        }

        List<Object> resources() {
            return flatten(body.get("Resource"));
        }

        Object condition() {
            return body.get("Condition");
        }

        boolean matchesAction(String action) {
            return actions().stream().anyMatch(pattern -> glob(pattern, action, true));
        }

        /**
         * A literal resource matches '*' or a wildcard pattern; an intrinsic (Fn::Sub, Fn::GetAtt,
         * Ref) matches only '*' or the same intrinsic, because it cannot be resolved here.
         */
        boolean matchesResource(Object resource) {
            for (Object granted : resources()) {
                if ("*".equals(granted)) {
                    return true;
                }
                if (granted instanceof String pattern && resource instanceof String literal
                        && glob(pattern, literal, false)) {
                    return true;
                }
                if (canonical(granted).equals(canonical(resource))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return source + "/" + sid();
        }
    }

    /**
     * Keeps CloudFormation's local tags. {@code !Ref} and {@code !Condition} keep their names;
     * every other {@code !Name} becomes {@code Fn::Name}.
     */
    private static final class TagPreservingConstructor extends SafeConstructor {

        TagPreservingConstructor(LoaderOptions options) {
            super(options);
            this.yamlMultiConstructors.put("!", new AbstractConstruct() {
                @Override
                public Object construct(Node node) {
                    String function = node.getTag().getValue().substring(1);
                    String key = "Ref".equals(function) || "Condition".equals(function) ? function : "Fn::" + function;
                    Object value;
                    if (node instanceof ScalarNode scalar) {
                        value = constructScalar(scalar);
                    } else if (node instanceof SequenceNode sequence) {
                        value = constructSequence(sequence);
                    } else {
                        value = constructMapping((MappingNode) node);
                    }
                    Map<String, Object> intrinsic = new LinkedHashMap<>();
                    intrinsic.put(key, value);
                    return intrinsic;
                }
            });
        }
    }
}
