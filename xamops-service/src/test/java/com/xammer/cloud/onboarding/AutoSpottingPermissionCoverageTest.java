package com.xammer.cloud.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.onboarding.CfnTemplate.Statement;
import com.xammer.cloud.service.autospotting.aws.AwsSessionPolicies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.xammer.cloud.onboarding.CfnTemplate.allowsAction;
import static com.xammer.cloud.onboarding.CfnTemplate.glob;
import static com.xammer.cloud.onboarding.CfnTemplate.statementsOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-23b-05: the AWS actions the AutoSpotting code calls must be granted by the served read-write templates
 * (prod, uat, local: {@code XamOpsAutoSpottingPolicy}) and by the STS session policies. Found live: the event
 * consumer called {@code ec2:DescribeTags}, the template did not grant it, and 67 events were dead-lettered.
 *
 * <p>The calls are read from the source, not listed here: every {@code view.<client>().<call>(...)} on the AWS
 * read view and every call on {@code AwsMutatingClient}'s Auto Scaling client. A new SDK call that no template or
 * session policy grants fails this test. Static: no AWS call.
 */
class AutoSpottingPermissionCoverageTest {
    private static final Path SOURCES = Path.of("src", "main", "java", "com", "xammer", "cloud", "service",
            "autospotting");
    private static final Path MUTATING_CLIENT = SOURCES.resolve("aws").resolve("AwsMutatingClient.java");
    private static final Path CLOUDFORMATION = Path.of("..", "cloudformation");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** AwsReadOnlyView accessor to IAM service prefix. */
    private static final Map<String, String> READ_CLIENTS = Map.of(
            "ec2", "ec2", "autoScaling", "autoscaling", "iam", "iam", "cloudFormation", "cloudformation",
            "cloudWatch", "cloudwatch", "ecs", "ecs", "elbv2", "elasticloadbalancing",
            "serviceQuotas", "servicequotas", "eventBridge", "events");

    private static final Pattern READ_CALL = Pattern.compile("\\b(\\w+)\\(\\)\\s*\\.\\s*(\\w+)\\(");
    private static final Pattern MUTATING_CALL = Pattern.compile("\\bautoScaling\\s*\\.\\s*(\\w+)\\(");

    enum Variant {
        PROD("xamops-readwrite-role.yml"), UAT("xamops-readwrite-role-uat.yml"),
        LOCAL("xamops-readwrite-role.local.yml");
        final String file;

        Variant(String file) {
            this.file = file;
        }
    }

    private static String iamAction(String prefix, String method) {
        return prefix + ":" + Character.toUpperCase(method.charAt(0)) + method.substring(1);
    }

    private static String source(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The IAM actions of every read-view SDK call in the AutoSpotting sources. */
    static Set<String> readActions() {
        Set<String> actions = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = READ_CALL.matcher(source(file));
                while (m.find()) {
                    String prefix = READ_CLIENTS.get(m.group(1));
                    if (prefix != null) {
                        actions.add(iamAction(prefix, m.group(2)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return actions;
    }

    /** The IAM actions of the write client (Auto Scaling only). */
    static Set<String> writeActions() {
        Set<String> actions = new TreeSet<>();
        Matcher m = MUTATING_CALL.matcher(source(MUTATING_CLIENT));
        while (m.find()) {
            if (!"close".equals(m.group(1))) { // closing the client is not an API call
                actions.add(iamAction("autoscaling", m.group(1)));
            }
        }
        return actions;
    }

    /**
     * The scan only sees {@code accessor().call(} chains (a client held in a local, passed to a helper or used as a method
     * reference is invisible), so it also fails when {@code AwsReadOnlyView} gains a client accessor that READ_CLIENTS
     * does not map (review Medium #8, Low #14).
     */
    @Test
    void everyClientAccessorOfTheReadViewIsMapped() {
        List<String> unmapped = java.util.Arrays.stream(com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView.class
                        .getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()) && m.getParameterCount() == 0
                        && m.getReturnType().getSimpleName().endsWith("Client"))
                .map(java.lang.reflect.Method::getName).filter(n -> !READ_CLIENTS.containsKey(n)).sorted().toList();
        assertThat(unmapped).as("AwsReadOnlyView client accessors missing from READ_CLIENTS").isEmpty();
        assertThat(readActions()).contains("events:DescribeRule");
    }
    /** Scoped grants must stay scoped: presence of the action alone is not coverage. */
    @ParameterizedTest
    @EnumSource(Variant.class)
    void scopedActionsAreGrantedOnlyWithTheirScope(Variant variant) {
        CfnTemplate template = CfnTemplate.load(CLOUDFORMATION.resolve(variant.file));
        List<Statement> statements = statementsOf(template.properties("XamOpsAutoSpottingPolicy").get("PolicyDocument"),
                "XamOpsAutoSpottingPolicy");
        for (String action : List.of("iam:PassRole", "ec2:RunInstances", "ec2:CreateTags")) {
            List<Statement> granting = statements.stream().filter(st -> st.isAllow() && st.matchesAction(action)).toList();
            assertThat(granting).as(variant.file + " " + action).isNotEmpty();
            assertThat(granting).as(variant.file + " " + action + " must carry a Condition")
                    .allMatch(st -> st.condition() != null);
        }
        List<Statement> getRole = statements.stream().filter(st -> st.isAllow() && st.matchesAction("iam:GetRole")).toList();
        assertThat(getRole).isNotEmpty();
        assertThat(getRole).as(variant.file + " iam:GetRole must be limited to the forwarder role")
                .allMatch(st -> !"\"*\"".equals(CfnTemplate.canonical(st.body().get("Resource"))));
    }
    @Test
    void theScanFindsTheCallsItIsMeantToCheck() {
        assertThat(readActions()).contains("autoscaling:DescribeAutoScalingGroups", "ec2:DescribeInstances",
                "ec2:DescribeTags", "iam:GetRole", "iam:SimulatePrincipalPolicy");
        assertThat(writeActions()).containsExactlyInAnyOrder("autoscaling:UpdateAutoScalingGroup",
                "autoscaling:CreateOrUpdateTags", "autoscaling:DeleteTags");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void everyActionTheCodeCallsIsGrantedByTheTemplate(Variant variant) {
        CfnTemplate template = CfnTemplate.load(CLOUDFORMATION.resolve(variant.file));
        List<Statement> statements = statementsOf(template.properties("XamOpsAutoSpottingPolicy").get("PolicyDocument"),
                "XamOpsAutoSpottingPolicy");
        List<String> missing = new ArrayList<>();
        Set<String> all = new TreeSet<>(readActions());
        all.addAll(writeActions());
        for (String action : all) {
            if (!allowsAction(statements, action)) {
                missing.add(action);
            }
        }
        assertThat(missing).as(variant.file + " does not grant").isEmpty();
    }

    @Test
    void everyActionTheCodeCallsIsGrantedByTheSessionPolicy() throws IOException {
        AwsSessionPolicies policies = new AwsSessionPolicies("arn:aws:iam::111122223333:role/XamOpsReadWriteRole");
        Set<String> readGranted = allowedActions(policies.serialize(AwsSessionPolicies.Mode.READ));
        Set<String> writeGranted = allowedActions(policies.serialize(AwsSessionPolicies.Mode.WRITE));
        List<String> missingRead = readActions().stream().filter(a -> !granted(readGranted, a)).toList();
        List<String> missingWrite = new ArrayList<>(readActions().stream().filter(a -> !granted(writeGranted, a)).toList());
        writeActions().stream().filter(a -> !granted(writeGranted, a)).forEach(missingWrite::add);
        assertThat(missingRead).as("READ session policy does not grant").isEmpty();
        // A wildcard satisfies any check, so the exact form is pinned where it ships: READ must list its actions
        // (the Describe* fallback would make this assertion vacuous), and WRITE must list the writes by name.
        assertThat(policies.usesDescribeFallback(AwsSessionPolicies.Mode.READ))
                .as("READ session policy fell back to Describe*; shrink its action list").isFalse();
        assertThat(readActions().stream().filter(a -> !readGranted.contains(a)).toList())
                .as("READ session policy must list each read action by name").isEmpty();
        assertThat(writeActions().stream().filter(a -> !writeGranted.contains(a)).toList())
                .as("WRITE session policy must list each write action by name").isEmpty();
        assertThat(missingWrite).as("WRITE session policy does not grant").isEmpty();
    }

    private static boolean granted(Set<String> patterns, String action) {
        return patterns.stream().anyMatch(p -> glob(p, action, true));
    }

    /** The action patterns of the Allow statements of a minified session policy. */
    private static Set<String> allowedActions(String policyJson) throws IOException {
        Set<String> out = new TreeSet<>();
        for (JsonNode s : JSON.readTree(policyJson).path("Statement")) {
            if (!"Allow".equals(s.path("Effect").asText())) {
                continue;
            }
            JsonNode a = s.path("Action");
            if (a.isArray()) {
                a.forEach(n -> out.add(n.asText()));
            } else {
                out.add(a.asText());
            }
        }
        return out;
    }
}
