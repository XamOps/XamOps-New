package com.xammer.cloud.service.gcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GCP REST calls behind AutoSpotting: the rollout policy that keeps capacity, conflict
 * handling, and the accelerator-aware On-Demand clone. Every request goes to a
 * {@link MockRestServiceServer}; {@code getAccessToken} is overridden, so nothing leaves the JVM.
 */
class GcpRestTemplateServiceTest {

    private static final String API = "https://compute.googleapis.com/compute/v1/projects/p";
    private static final String TEMPLATE_URL =
            "https://www.googleapis.com/compute/v1/projects/p/global/instanceTemplates/web-od-1";

    private GcpRestTemplateService svc;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        svc = new GcpRestTemplateService(mock(CloudAccountRepository.class)) {
            @Override
            String getAccessToken(String p) {
                return "test-token";
            }
        };
        server = MockRestServiceServer
                .bindTo((RestTemplate) ReflectionTestUtils.getField(svc, "restTemplate"))
                .build();
    }

    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    // ── startRollingUpdate ──────────────────────────────────────────────────

    @Test
    void regionalRollout_surgesOnePerZone_andNeverRemovesCapacity() throws Exception {
        server.expect(requestTo(API + "/regions/us-central1/instanceGroupManagers/mig"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(jsonPath("$.instanceTemplate").value(TEMPLATE_URL))
                .andExpect(jsonPath("$.updatePolicy.type").value("PROACTIVE"))
                .andExpect(jsonPath("$.updatePolicy.maxSurge.fixed").value(3))
                .andExpect(jsonPath("$.updatePolicy.maxUnavailable.fixed").value(0))
                .andExpect(jsonPath("$.updatePolicy.minReadySeconds").doesNotExist())
                .andRespond(withSuccess(json("{'name':'op-1','region':'" + API
                        + "/regions/us-central1','status':'DONE'}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/regions/us-central1/operations/op-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json("{'status':'DONE'}"), MediaType.APPLICATION_JSON));

        svc.startRollingUpdate("p", "us-central1", "mig", TEMPLATE_URL, 3);

        server.verify();
    }

    @Test
    void zonalRollout_surgesOne_andNeverRemovesCapacity() throws Exception {
        server.expect(requestTo(API + "/zones/us-central1-a/instanceGroupManagers/mig"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.updatePolicy.maxSurge.fixed").value(1))
                .andExpect(jsonPath("$.updatePolicy.maxUnavailable.fixed").value(0))
                .andExpect(jsonPath("$.updatePolicy.minReadySeconds").doesNotExist())
                .andRespond(withSuccess(json("{'name':'op-z','zone':'" + API
                        + "/zones/us-central1-a','status':'DONE'}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/zones/us-central1-a/operations/op-z"))
                .andRespond(withSuccess(json("{'status':'DONE'}"), MediaType.APPLICATION_JSON));

        svc.startRollingUpdate("p", "us-central1-a", "mig", TEMPLATE_URL, 0);

        server.verify();
    }

    @Test
    void regionalRollout_withUnknownZoneCount_failsBeforeAnyRequest() {
        assertThatThrownBy(() -> svc.startRollingUpdate("p", "us-central1", "mig", TEMPLATE_URL, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot determine the zone count for regional MIG mig");

        server.verify(); // no expectations: any request would have failed the test
    }

    @Test
    void rolloutConflict409_isTypedConflict() {
        server.expect(requestTo(API + "/regions/us-central1/instanceGroupManagers/mig"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> svc.startRollingUpdate("p", "us-central1", "mig", TEMPLATE_URL, 3))
                .isInstanceOfSatisfying(AutoSpottingBlockedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AutoSpottingBlockedException.GCP_MIG_UPDATE_CONFLICT);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        server.verify();
    }

    @Test
    void rolloutPreconditionFailed412_isTypedConflict() {
        server.expect(requestTo(API + "/zones/us-central1-a/instanceGroupManagers/mig"))
                .andRespond(withStatus(HttpStatus.PRECONDITION_FAILED));

        assertThatThrownBy(() -> svc.startRollingUpdate("p", "us-central1-a", "mig", TEMPLATE_URL, 1))
                .isInstanceOfSatisfying(AutoSpottingBlockedException.class, e ->
                        assertThat(e.getCode()).isEqualTo(AutoSpottingBlockedException.GCP_MIG_UPDATE_CONFLICT));
        server.verify();
    }

    @Test
    void rolloutOtherClientError_staysGenericFailure() {
        server.expect(requestTo(API + "/regions/us-central1/instanceGroupManagers/mig"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> svc.startRollingUpdate("p", "us-central1", "mig", TEMPLATE_URL, 3))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(AutoSpottingBlockedException.class)
                .hasMessageContaining("Rolling update PATCH failed");
        server.verify();
    }

    @Test
    void rolloutOperationWithError_fails() {
        server.expect(requestTo(API + "/regions/us-central1/instanceGroupManagers/mig"))
                .andRespond(withSuccess(json("{'name':'op-1','region':'" + API
                        + "/regions/us-central1','status':'RUNNING'}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/regions/us-central1/operations/op-1"))
                .andRespond(withSuccess(json("{'status':'DONE','error':{'errors':[{'code':'QUOTA_EXCEEDED'}]}}"),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> svc.startRollingUpdate("p", "us-central1", "mig", TEMPLATE_URL, 3))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Operation failed")
                .hasMessageContaining("QUOTA_EXCEEDED");
        server.verify();
    }

    // ── onHostMaintenanceForStandard ────────────────────────────────────────

    private static JsonObject props(String machineType) {
        JsonObject p = new JsonObject();
        p.addProperty("machineType", machineType);
        return p;
    }

    private static JsonObject withT4(JsonObject p) {
        p.add("guestAccelerators", JsonParser.parseString(
                json("[{'acceleratorType':'nvidia-tesla-t4','acceleratorCount':1}]")));
        return p;
    }

    @Test
    void onHostMaintenance_generalPurposeMigrates() {
        assertThat(GcpRestTemplateService.onHostMaintenanceForStandard(props("e2-standard-4"))).isEqualTo("MIGRATE");
        assertThat(GcpRestTemplateService.onHostMaintenanceForStandard(props("n2-standard-8"))).isEqualTo("MIGRATE");
        assertThat(GcpRestTemplateService.onHostMaintenanceForStandard(null)).isEqualTo("MIGRATE");
    }

    @Test
    void onHostMaintenance_attachedGpuTerminates() {
        assertThat(GcpRestTemplateService.onHostMaintenanceForStandard(withT4(props("n1-standard-4"))))
                .isEqualTo("TERMINATE");
        JsonObject emptyAccelerators = props("n1-standard-4");
        emptyAccelerators.add("guestAccelerators", new JsonArray());
        assertThat(GcpRestTemplateService.onHostMaintenanceForStandard(emptyAccelerators)).isEqualTo("MIGRATE");
    }

    @Test
    void onHostMaintenance_acceleratorOptimizedBareMetalAndH4dTerminate() {
        for (String type : new String[] {"a2-highgpu-1g", "a3-highgpu-8g", "a4-highgpu-8g", "a4x-highgpu-4g",
                "g2-standard-8", "g4-standard-48", "c3-standard-192-metal", "h4d-highmem-192"}) {
            assertThat(GcpRestTemplateService.onHostMaintenanceForStandard(props(type)))
                    .as(type).isEqualTo("TERMINATE");
        }
    }

    @Test
    void onHostMaintenance_handlesFullMachineTypeUrl() {
        assertThat(GcpRestTemplateService.onHostMaintenanceForStandard(props(
                "https://www.googleapis.com/compute/v1/projects/p/zones/us-central1-a/machineTypes/a2-highgpu-1g")))
                .isEqualTo("TERMINATE");
        assertThat(GcpRestTemplateService.onHostMaintenanceForStandard(props(
                "https://www.googleapis.com/compute/v1/projects/p/zones/us-central1-a/machineTypes/e2-medium")))
                .isEqualTo("MIGRATE");
    }

    // ── createSpotTemplate (standard clone) ─────────────────────────────────

    @Test
    void standardClone_keepsGpus_andTerminatesOnMaintenance() throws Exception {
        JsonObject source = new JsonObject();
        source.addProperty("name", "web-spot-1");
        JsonObject properties = withT4(props("n1-standard-4"));
        JsonObject scheduling = new JsonObject();
        scheduling.addProperty("provisioningModel", "SPOT");
        properties.add("scheduling", scheduling);
        source.add("properties", properties);

        server.expect(requestTo(API + "/global/instanceTemplates"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("web-od-1"))
                .andExpect(jsonPath("$.properties.guestAccelerators[0].acceleratorType").value("nvidia-tesla-t4"))
                .andExpect(jsonPath("$.properties.guestAccelerators[0].acceleratorCount").value(1))
                .andExpect(jsonPath("$.properties.scheduling.provisioningModel").value("STANDARD"))
                .andExpect(jsonPath("$.properties.scheduling.onHostMaintenance").value("TERMINATE"))
                .andExpect(jsonPath("$.properties.scheduling.automaticRestart").value(true))
                .andRespond(withSuccess(json("{'name':'op-2','status':'RUNNING'}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/global/operations/op-2"))
                .andRespond(withSuccess(json("{'status':'DONE'}"), MediaType.APPLICATION_JSON));

        svc.createSpotTemplate("p", null, "web-od-1", source, false);

        server.verify();
    }

    @Test
    void isSpotTemplateJson_readsProvisioningModelAndLegacyPreemptible() {
        assertThat(GcpRestTemplateService.isSpotTemplateJson(JsonParser.parseString(
                json("{'properties':{'scheduling':{'provisioningModel':'SPOT'}}}")).getAsJsonObject())).isTrue();
        assertThat(GcpRestTemplateService.isSpotTemplateJson(JsonParser.parseString(
                json("{'properties':{'scheduling':{'preemptible':true}}}")).getAsJsonObject())).isTrue();
        assertThat(GcpRestTemplateService.isSpotTemplateJson(JsonParser.parseString(
                json("{'properties':{'scheduling':{'provisioningModel':'STANDARD'}}}")).getAsJsonObject())).isFalse();
        assertThat(GcpRestTemplateService.isSpotTemplateJson(null)).isFalse();
    }
}
