package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The GCP REST facades against a {@link MockRestServiceServer}: nothing leaves the JVM. */
class GcpMigClientTest {
    private static final String API = "https://compute.googleapis.com/compute/v1/projects/xamops-test";
    private final RestTemplate readTemplate = new RestTemplate();
    private final RestTemplate mutationTemplate = new RestTemplate();
    private MockRestServiceServer reads;
    private MockRestServiceServer mutations;
    private GcpMigClient client;

    @BeforeEach
    void setUp() {
        client = new GcpMigClient(p -> Optional.of("test-token"), readTemplate, mutationTemplate);
        reads = MockRestServiceServer.bindTo(readTemplate).build();
        mutations = MockRestServiceServer.bindTo(mutationTemplate).build();
    }

    private static String json(String s) {
        return s.replace('\'', '"');
    }

    @Test
    void getMigSendsTheBearerTokenToTheRegionalOrZonalUrl() {
        reads.expect(requestTo(API + "/regions/us-central1/instanceGroupManagers/web-mig"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(withSuccess(json("{'name':'web-mig','targetSize':6}"), MediaType.APPLICATION_JSON));
        reads.expect(requestTo(API + "/zones/us-central1-a/instanceGroupManagers/batch-mig"))
                .andRespond(withSuccess(json("{'name':'batch-mig'}"), MediaType.APPLICATION_JSON));
        GcpMigClient.ReadOnly ro = client.readOnly("xamops-test");
        assertThat(ro.getMig(true, "us-central1", "web-mig").path("targetSize").asInt()).isEqualTo(6);
        assertThat(ro.getMig(false, "us-central1-a", "batch-mig").path("name").asText()).isEqualTo("batch-mig");
        reads.verify();
    }

    @Test
    void aggregatedListFlattensEveryScopeAndFollowsPages() {
        reads.expect(requestTo(API + "/aggregated/instanceGroupManagers?maxResults=500"))
                .andRespond(withSuccess(json("{'items':{'zones/a':{'instanceGroupManagers':[{'name':'m1'}]},"
                        + "'regions/r':{'instanceGroupManagers':[{'name':'m2'}]}},'nextPageToken':'t2'}"),
                        MediaType.APPLICATION_JSON));
        reads.expect(requestTo(API + "/aggregated/instanceGroupManagers?maxResults=500&pageToken=t2"))
                .andRespond(withSuccess(json("{'items':{'zones/b':{'warning':{'code':'NO_RESULTS_ON_PAGE'}}}}"),
                        MediaType.APPLICATION_JSON));
        List<JsonNode> migs = client.readOnly("xamops-test").aggregatedListMigs();
        assertThat(migs).extracting(m -> m.path("name").asText()).containsExactlyInAnyOrder("m1", "m2");
        reads.verify();
    }

    @Test
    void operationErrorIsFailureNotSuccess() throws Exception {
        JsonNode done = FakeGcp.JSON.readTree(json("{'status':'DONE','error':{'errors':["
                + "{'code':'ZONE_RESOURCE_POOL_EXHAUSTED','message':'raw text never surfaced'}]}}"));
        GcpErrorMapper.OperationOutcome outcome = GcpErrorMapper.operationOutcome(done);
        assertThat(outcome.done()).isTrue();
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.errorKind()).isEqualTo(ErrorKind.CAPACITY);
        // A DONE operation with no error is the only success; a running one is neither.
        assertThat(GcpErrorMapper.operationOutcome(FakeGcp.JSON.readTree("{\"status\":\"DONE\"}")).success()).isTrue();
        assertThat(GcpErrorMapper.operationOutcome(FakeGcp.JSON.readTree("{\"status\":\"RUNNING\"}")).done()).isFalse();
        // And through the facade: one poll, never a blocking get.
        reads.expect(requestTo(API + "/zones/us-central1-a/operations/op-1"))
                .andRespond(withSuccess(done.toString(), MediaType.APPLICATION_JSON));
        JsonNode polled = client.readOnly("xamops-test").getOperation("zones", "us-central1-a", "op-1");
        assertThat(GcpErrorMapper.operationOutcome(polled).failed()).isTrue();
    }

    @Test
    void a409OnPatchIsConflictNeverSuccess() {
        UUID requestId = UUID.nameUUIDFromBytes("op-1:3".getBytes());
        mutations.expect(requestTo(API + "/regions/us-central1/instanceGroupManagers/web-mig?requestId=" + requestId))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(jsonPath("$.fingerprint").value("Zm9vYmFy"))
                .andExpect(jsonPath("$.versions[0].name").value("xas-spot"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body(json("{'error':{'errors':[{'reason':'resourceNotReady'}]}}")));
        GcpMigClient.Mutating mu = client.mutating("xamops-test");
        assertThatThrownBy(() -> mu.patchMig(true, "us-central1", "web-mig",
                FakeGcp.JSON.readTree(json("{'versions':[{'name':'xas-spot'}]}")), "Zm9vYmFy", requestId))
                .isInstanceOfSatisfying(GcpReadException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ErrorKind.CONFLICT);
                    assertThat(e.httpStatus()).isEqualTo(409);
                });
        mutations.verify();
    }

    @Test
    void a412OnPatchIsConflictToo() {
        UUID requestId = UUID.randomUUID();
        mutations.expect(requestTo(API + "/zones/us-central1-a/instanceGroupManagers/batch-mig?requestId=" + requestId))
                .andRespond(withStatus(HttpStatus.PRECONDITION_FAILED));
        assertThatThrownBy(() -> client.mutating("xamops-test").patchMig(false, "us-central1-a", "batch-mig",
                FakeGcp.JSON.createObjectNode(), "fp", requestId))
                .isInstanceOfSatisfying(GcpReadException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.CONFLICT));
    }

    @Test
    void insertAndDeleteTemplatesCarryTheRequestId() {
        UUID requestId = UUID.randomUUID();
        mutations.expect(requestTo(API + "/global/instanceTemplates?requestId=" + requestId))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json("{'name':'op-2','status':'RUNNING'}"), MediaType.APPLICATION_JSON));
        mutations.expect(requestTo(API + "/regions/us-central1/instanceTemplates/xas-1?requestId=" + requestId))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess(json("{'name':'op-3'}"), MediaType.APPLICATION_JSON));
        GcpMigClient.Mutating mu = client.mutating("xamops-test");
        assertThat(mu.insertInstanceTemplate(null, FakeGcp.JSON.createObjectNode().put("name", "xas-1"), requestId)
                .path("name").asText()).isEqualTo("op-2");
        mu.deleteInstanceTemplate("us-central1", "xas-1", requestId);
        mutations.verify();
        assertThatThrownBy(() -> mu.insertInstanceTemplate(null, FakeGcp.JSON.createObjectNode(), null))
                .isInstanceOf(GcpReadException.class);
    }

    @Test
    void httpStatusesMapToErrorKinds() {
        assertThat(GcpErrorMapper.fromHttp(403, "forbidden")).isEqualTo(ErrorKind.PERMISSION);
        assertThat(GcpErrorMapper.fromHttp(403, "rateLimitExceeded")).isEqualTo(ErrorKind.THROTTLED);
        assertThat(GcpErrorMapper.fromHttp(429, null)).isEqualTo(ErrorKind.THROTTLED);
        assertThat(GcpErrorMapper.fromHttp(404, null)).isEqualTo(ErrorKind.NOT_FOUND);
        assertThat(GcpErrorMapper.fromHttp(409, null)).isEqualTo(ErrorKind.CONFLICT);
        assertThat(GcpErrorMapper.fromHttp(412, "conditionNotMet")).isEqualTo(ErrorKind.CONFLICT);
        assertThat(GcpErrorMapper.fromHttp(500, null)).isEqualTo(ErrorKind.TRANSIENT);
        assertThat(GcpErrorMapper.fromHttp(400, "invalid")).isEqualTo(ErrorKind.VALIDATION);
        assertThat(GcpErrorMapper.fromOperationCode("QUOTA_EXCEEDED")).isEqualTo(ErrorKind.CAPACITY);
    }

    @Test
    void aFailedReadIsMappedAndNeverCarriesTheResponseBody() {
        reads.expect(requestTo(API + "/regions/us-central1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body(json("{'error':{'errors':[{'reason':'forbidden'}],'message':'secret-token-abc'}}")));
        assertThatThrownBy(() -> client.readOnly("xamops-test").getRegion("us-central1"))
                .isInstanceOfSatisfying(GcpReadException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ErrorKind.PERMISSION);
                    assertThat(e.toString()).doesNotContain("secret-token-abc");
                });
    }

    @Test
    void aMissingTokenIsPermissionAndNoRequestIsSent() {
        GcpMigClient noToken = new GcpMigClient(p -> Optional.empty(), readTemplate, mutationTemplate);
        assertThatThrownBy(() -> noToken.readOnly("xamops-test").getRegion("us-central1"))
                .isInstanceOfSatisfying(GcpReadException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.PERMISSION));
        reads.verify();
    }

    @Test
    void urlSegmentsAreValidated() {
        assertThatThrownBy(() -> client.readOnly("Bad_Project")).isInstanceOf(GcpReadException.class);
        GcpMigClient.ReadOnly ro = client.readOnly("xamops-test");
        assertThatThrownBy(() -> ro.getMig(true, "us-central1", "../x")).isInstanceOf(GcpReadException.class);
        assertThatThrownBy(() -> ro.getMig(true, "us central", "mig")).isInstanceOf(GcpReadException.class);
        assertThatThrownBy(() -> ro.getInstanceTemplate("https://evil.example/compute/v1/projects/p/global/instanceTemplates/t"))
                .isInstanceOf(GcpReadException.class);
    }

    @Test
    void timeoutsAreTheCoreRetrySettings() {
        assertThat(GcpMigClient.READ_TIMEOUTS.connect()).isEqualTo(Duration.ofSeconds(10));
        assertThat(GcpMigClient.READ_TIMEOUTS.read()).isEqualTo(Duration.ofSeconds(30));
        assertThat(GcpMigClient.MUTATION_TIMEOUTS.connect()).isEqualTo(Duration.ofSeconds(15));
        assertThat(GcpMigClient.MUTATION_TIMEOUTS.read()).isEqualTo(Duration.ofSeconds(45));
        // The production constructor builds both clients from the shared credentials provider without a call.
        assertThat(new GcpMigClient(mock(GcpClientProvider.class)).readOnly("xamops-test").project()).isEqualTo("xamops-test");
    }

    @Test
    void theReadOnlyFacadeExposesOnlyReadShapedMethods() {
        for (Method m : GcpMigClient.ReadOnly.class.getDeclaredMethods()) {
            String n = m.getName();
            assertThat(Arrays.asList("get", "list", "aggregatedList", "testIamPermissions", "project")
                    .stream().anyMatch(n::startsWith)).as(n).isTrue();
        }
        assertThat(GcpReadOnlyView.class.getDeclaredMethods())
                .noneMatch(m -> m.getReturnType().equals(GcpMigClient.Mutating.class));
    }
}
