package com.xammer.cloud.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.k8s.K8sRefusalBody;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reason codes must match the fixture the frontend reads too
 * ({@code src/test/reasonCodes.contract.test.ts}). Either side's test fails when its own list
 * drifts from {@code /k8s/k8s-reason-codes.json} (design §17 "Contract fixture").
 */
class K8sAutomationReasonContractTest {

    private static JsonNode fixture;

    @BeforeAll
    static void loadFixture() throws Exception {
        try (InputStream in = K8sAutomationReasonContractTest.class.getResourceAsStream("/k8s/k8s-reason-codes.json")) {
            assertThat(in).as("fixture /k8s/k8s-reason-codes.json on the test classpath").isNotNull();
            fixture = new ObjectMapper().readTree(in);
        }
    }

    private static List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        fixture.get(key).forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void gateCodesMatchTheFixtureInOrder() {
        List<String> gates = Arrays.stream(K8sAutomationReason.values())
                .filter(K8sAutomationReason::gate)
                .map(Enum::name)
                .toList();
        assertThat(gates).containsExactlyElementsOf(strings("gates"));
        assertThat(gates).hasSize(16);
    }

    @Test
    void errorCodesMatchTheFixtureInOrder() {
        List<String> errors = Arrays.stream(K8sAutomationReason.values())
                .filter(r -> !r.gate())
                .map(Enum::name)
                .toList();
        assertThat(errors).containsExactlyElementsOf(strings("errors"));
    }

    @Test
    void gatesComeBeforeErrors() {
        K8sAutomationReason[] values = K8sAutomationReason.values();
        int firstError = Arrays.asList(values).indexOf(K8sAutomationReason.UNAUTHENTICATED);
        for (int i = 0; i < values.length; i++) {
            assertThat(values[i].gate()).as(values[i].name()).isEqualTo(i < firstError);
        }
    }

    @Test
    void noUserPermissionDetailsMatchTheFixture() {
        List<String> details = Arrays.stream(NoUserPermissionDetail.values()).map(Enum::name).toList();
        assertThat(details).containsExactlyElementsOf(strings("noUserPermissionDetails"));
    }

    @Test
    void everyCodeIsThePrefixPlusTheName() {
        assertThat(fixture.get("codePrefix").asText()).isEqualTo(K8sAutomationReason.CODE_PREFIX).isEqualTo("K8S_");
        for (K8sAutomationReason r : K8sAutomationReason.values()) {
            assertThat(r.code()).isEqualTo("K8S_" + r.name());
            assertThat(r.defaultMessage()).as(r.name()).isNotBlank();
        }
    }

    @Test
    void errorCodesCarryTheirDocumentedStatus() {
        Map<K8sAutomationReason, HttpStatus> expected = Map.of(
                K8sAutomationReason.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
                K8sAutomationReason.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                K8sAutomationReason.INVALID_POLICY, HttpStatus.BAD_REQUEST,
                K8sAutomationReason.NO_ACCOUNT_ACCESS, HttpStatus.FORBIDDEN,
                K8sAutomationReason.CLUSTER_NOT_REGISTERED, HttpStatus.NOT_FOUND,
                K8sAutomationReason.INTERNAL, HttpStatus.INTERNAL_SERVER_ERROR);
        expected.forEach((reason, status) -> assertThat(reason.status()).as(reason.name()).isEqualTo(status));
        Arrays.stream(K8sAutomationReason.values()).filter(K8sAutomationReason::gate)
                .forEach(r -> assertThat(r.status()).as(r.name()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void refusalBodyCarriesDetailOnlyForNoUserPermission() {
        K8sRefusalBody permission = K8sRefusalBody.of(
                K8sAutomationRefusedException.noUserPermission(NoUserPermissionDetail.IMPERSONATION));
        assertThat(permission.success()).isFalse();
        assertThat(permission.code()).isEqualTo("K8S_NO_USER_PERMISSION");
        assertThat(permission.detail()).isEqualTo("IMPERSONATION");
        assertThat(permission.message()).isEqualTo(NoUserPermissionDetail.IMPERSONATION.message());

        K8sAutomationRefusedException readOnly = new K8sAutomationRefusedException(HttpStatus.FORBIDDEN,
                K8sAutomationReason.READ_ONLY_CONNECTION, null, null, NoUserPermissionDetail.TAB_GRANT);
        assertThat(readOnly.getDetail()).isNull();
        K8sRefusalBody body = K8sRefusalBody.of(readOnly);
        assertThat(body.detail()).isNull();
        assertThat(body.field()).isNull();
        assertThat(body.message()).isEqualTo("Read-only connection. Automation changes need read-write access.");
    }
}
