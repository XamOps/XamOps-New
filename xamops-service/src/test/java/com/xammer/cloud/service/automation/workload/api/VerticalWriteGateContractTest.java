package com.xammer.cloud.service.automation.workload.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import com.xammer.cloud.service.automation.workload.execute.DirectInPlaceResizeExecutor;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The write-gate contract between endpoint 1, endpoint 3 and the Automation tab, pinned to the
 * bytes the frontend test reads.
 *
 * <h2>Why this exists</h2>
 *
 * On 2026-10-12 a UAT user whose {@code user_tab_access} rows held {@code cloudops-cloudk8s} but not
 * {@code cloudops-cloudk8s-automation} clicked an enabled Apply and got
 * {@code 403 K8S_NO_USER_PERMISSION} on every workload. Endpoint 1 on that build does report the
 * gate for that caller; the tab had been loaded while the frontend was already new and the backend
 * was not, and absence of a gate code was the only "you may apply" signal there was.
 *
 * <p>This test drives the <strong>real</strong> controller, the real {@link VerticalApplyService}
 * and the real {@link AutomationWritePermission} for exactly that caller, serialises the real DTOs
 * with the production MVC mapper settings ({@code spring.http.converters.preferred-json-mapper=
 * jackson2}, {@code write-dates-as-timestamps=false}), and compares both bodies with
 * {@value #FIXTURE}. {@code verticalWriteGateContract.test.ts} feeds the same file through the
 * frontend adapter to the Apply button. A rename on either side fails one of the two tests.
 *
 * <p>To regenerate the file after a deliberate contract change:
 * {@code mvn -o -f xamops-service/pom.xml test -Dtest=VerticalWriteGateContractTest
 * -Dvertical.contract.write=true}, then run the frontend test.
 */
class VerticalWriteGateContractTest {

    static final String FIXTURE = "frontend-app/src/api/__fixtures__/verticalWriteGateContract.json";

    private static final String AWS_ID = "102227938259";
    private static final String QUERY = "?accountId=" + AWS_ID + "&region=ap-south-1";
    private static final String READ = "/api/eks/automation/vertical/xamops-test-1" + QUERY;
    private static final String APPLY = "/api/eks/automation/vertical/xamops-test-1/workloads/team-a/"
            + "Deployment/overprovisioned-api/apply" + QUERY;
    private static final String APPLY_BODY = """
            {"containers":[{"name":"idler","cpuRequestM":25,"memRequestMi":250,\
            "cpuLimitM":25,"memLimitMi":250}],"applyMethod":"IN_PLACE","dryRun":true}""";
    private static final Instant AT = Instant.parse("2026-10-12T10:00:00Z");

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private UserTabAccessRepository tabAccessRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Client client = new Client();
        client.setId(7L);
        CloudAccount account = new CloudAccount();
        account.setId(3L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);
        account.setAccessType("read-write");
        account.setStatus("CONNECTED");
        account.setClient(client);

        User user = new User();
        user.setId(11L);
        user.setUsername("restricted-user");
        user.setEmail("restricted@example.com");
        user.setRole("ROLE_XAMOPS");

        CloudAccountAccessService accessService = mock(CloudAccountAccessService.class);
        when(accessService.requireAwsAccess(any(), eq(AWS_ID))).thenReturn(account);
        when(accessService.canAccess(any(), any())).thenReturn(true);
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(11L)).thenReturn(Optional.of(user));
        tabAccessRepository = mock(UserTabAccessRepository.class);
        // The UAT shape: restricted to a tab list that has CloudK8s but not the automation key.
        when(tabAccessRepository.findTabNamesByUserId(11L))
                .thenReturn(List.of("cloudops-dashboard", AutomationWritePermission.TAB_CLOUDK8S));

        AutomationWritePermission permission = new AutomationWritePermission(accessService,
                userRepository, tabAccessRepository,
                mock(com.xammer.cloud.security.DemoClientMembership.class), () -> null);
        VerticalApplyService applyService = new VerticalApplyService(
                new WorkloadAutoscalingProperties(true, true, true), permission, List.of(),
                mock(ClusterWorkloadInventory.class),
                mock(com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader.class),
                mock(DirectInPlaceResizeExecutor.class),
                mock(WorkloadPolicyService.class),
                mock(com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder.class),
                VerticalReadServiceFixtures.metricsProperties("RECOMMEND", 72),
                Clock.fixed(AT, ZoneOffset.UTC));

        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(42L);
        config.setClusterName("xamops-test-1");
        config.setRegion("ap-south-1");
        KubernetesClusterConfigRepository configs = mock(KubernetesClusterConfigRepository.class);
        when(configs.findByCloudAccountIdAndClusterName(3L, "xamops-test-1")).thenReturn(Optional.of(config));

        // What the engine reported for overprovisioned-api on xamops-test-1 (UAT flags: RECOMMEND,
        // 72h), as real DTOs. The engine half is not what this contract is about, so it is built
        // rather than measured; everything about the caller is computed by the real classes.
        VerticalAutomationReadService readService = mock(VerticalAutomationReadService.class);
        when(readService.read(any(), any())).thenReturn(engineView());

        VerticalAutomationController controller = new VerticalAutomationController(
                accessService, configs, readService, applyService,
                mock(com.xammer.cloud.service.automation.workload.rca.RcaReadService.class),
                new com.xammer.cloud.service.automation.workload.rca.RcaProperties(
                        24, 10, 2, 3, 2, 2, 3));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();

        ClientUserDetails principal = new ClientUserDetails("restricted-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 7L, 11L, "default");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("endpoint 1 and endpoint 3 serialise the TAB_GRANT caller exactly as the frontend fixture says")
    void theWireMatchesTheFrontendFixture() throws Exception {
        MvcResult read = mvc.perform(get(READ)).andReturn();
        MvcResult apply = mvc.perform(post(APPLY)
                .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY)).andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(apply.getResponse().getStatus()).isEqualTo(403);

        ObjectNode actual = mapper.createObjectNode();
        actual.put("_comment", "Generated by VerticalWriteGateContractTest from the real backend DTOs."
                + " Do not edit by hand; regenerate with -Dvertical.contract.write=true.");
        actual.set("read", mapper.readTree(read.getResponse().getContentAsString(StandardCharsets.UTF_8)));
        actual.put("applyStatus", apply.getResponse().getStatus());
        actual.set("apply", mapper.readTree(apply.getResponse().getContentAsString(StandardCharsets.UTF_8)));

        // The field names and values the frontend reads, asserted as literals so the fixture
        // cannot drift into agreeing with a broken serialiser.
        JsonNode body = actual.get("read");
        assertThat(body.get("gateReasons").toString()).isEqualTo("[\"NO_USER_PERMISSION\"]");
        assertThat(body.get("gateDetails").toString()).isEqualTo("{\"NO_USER_PERMISSION\":\"TAB_GRANT\"}");
        assertThat(body.get("writeGatesChecked").asBoolean()).isTrue();
        JsonNode refusal = actual.get("apply");
        assertThat(refusal.get("code").asText()).isEqualTo("K8S_NO_USER_PERMISSION");
        assertThat(refusal.get("detail").asText()).isEqualTo("TAB_GRANT");
        assertThat(refusal.get("message").asText()).isEqualTo("You don't have permission to change"
                + " automation. Ask your XamOps administrator to allow automation changes for your user.");
        assertThat(refusal.has("field")).isFalse();

        Path fixture = fixturePath();
        if (Boolean.getBoolean("vertical.contract.write")) {
            Files.writeString(fixture, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
            return;
        }
        assertThat(fixture).as("the shared fixture the frontend contract test reads").exists();
        JsonNode expected = mapper.readTree(Files.readString(fixture));
        assertThat(actual)
                .as("endpoint 1 / endpoint 3 wire changed: regenerate %s with -Dvertical.contract.write=true"
                        + " and make verticalWriteGateContract.test.ts pass against it", FIXTURE)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("the same caller with both keys has no write gate, and is still marked as checked")
    void bothKeysClearTheGateButStillSayChecked() throws Exception {
        when(tabAccessRepository.findTabNamesByUserId(11L)).thenReturn(List.of(
                AutomationWritePermission.TAB_CLOUDK8S, AutomationWritePermission.TAB_CLOUDK8S_AUTOMATION));

        JsonNode body = mapper.readTree(mvc.perform(get(READ)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(body.get("gateReasons").toString()).isEqualTo("[]");
        assertThat(body.get("gateDetails").toString()).isEqualTo("{}");
        assertThat(body.get("writeGatesChecked").asBoolean()).isTrue();
    }

    private static Path fixturePath() {
        // Surefire runs with the module directory as the working directory.
        Path module = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        return module.resolve("..").resolve(FIXTURE).normalize();
    }

    private static VerticalEngineResponse engineView() {
        VerticalEngineResponse.EngineStatus engine = new VerticalEngineResponse.EngineStatus(true, true,
                "CONFIGURED", "DIRECT",
                new VerticalEngineResponse.ExecutionPathDetail(
                        new VerticalEngineResponse.PathAvailability(true, null),
                        new VerticalEngineResponse.AgentAvailability(false, ReasonCode.AGENT_NOT_INSTALLED, null)),
                new VerticalEngineResponse.MetricsSource(true, "prometheus-proxy"),
                new VerticalEngineResponse.LastCycle(AT, 1, null));
        VerticalWorkloadDto workload = new VerticalWorkloadDto(
                new VerticalWorkloadDto.WorkloadRefDto("team-a", "Deployment", "overprovisioned-api"),
                new VerticalWorkloadDto.PolicyDto("RECOMMEND", 25, 95, "72h", "IN_PLACE"),
                2,
                new VerticalWorkloadDto.HpaDto(false, null),
                null,
                null,
                List.of("IDLE"),
                List.of(new VerticalWorkloadDto.ContainerDto("idler", "Guaranteed",
                        new VerticalWorkloadDto.ResourcesDto(1000, 1024L, 1000, 1024L),
                        new VerticalWorkloadDto.ResourcesDto(25, 250L, 25, 250L),
                        List.of("FLOOR_CLAMPED", "LIMIT_MOVED"),
                        null)),
                null,
                false,
                new VerticalWorkloadDto.ProvenanceDto(95, 72, AT.minusSeconds(72 * 3600L), AT, 0.72,
                        "prometheus-proxy", "DEFAULTS"),
                new VerticalWorkloadDto.HorizontalDto("NOT_EVALUATED", null, null, null, List.of()));
        return new VerticalEngineResponse(engine, List.of(), List.of(workload));
    }
}
