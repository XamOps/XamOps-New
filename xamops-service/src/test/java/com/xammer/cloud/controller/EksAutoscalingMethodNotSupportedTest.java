package com.xammer.cloud.controller;

import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationRequestContext;
import com.xammer.cloud.service.ConsolidationPlanService;
import com.xammer.cloud.service.ConsolidationRunService;
import com.xammer.cloud.service.EksAutoscalingService;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import com.xammer.cloud.service.spot.SpotInterruptionReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * BUG-API-005: an unsupported HTTP method on a PUT-only /api/eks autoscaling route must answer 405,
 * not 500. HttpRequestMethodNotSupportedException is raised by Spring before a controller method is
 * chosen, so it reaches the {@link GlobalExceptionHandler} advice — wired here exactly like
 * AutomationUsageControllerTest does.
 *
 * <p>
 * Without the typed {@code @ExceptionHandler(HttpRequestMethodNotSupportedException.class)} in
 * {@link GlobalExceptionHandler}, this path falls to that advice's {@code Exception.class}
 * catch-all and comes back as 500 {"message":"An unexpected error occurred: Request method 'GET'
 * is not supported"}.
 */
class EksAutoscalingMethodNotSupportedTest {

    private static final String BASE = "/api/eks/autoscaling/prod-eks";

    private MockMvc mvc;
    private K8sAutomationRequestContext ctx;

    @BeforeEach
    void setUp() {
        EksAutoscalingService service = mock(EksAutoscalingService.class);
        ConsolidationPlanService consolidationPlanService = mock(ConsolidationPlanService.class);
        ConsolidationRunService consolidationRunService = mock(ConsolidationRunService.class);
        SpotInterruptionReadService spotReadService = mock(SpotInterruptionReadService.class);
        EksAutoscalingController controller = new EksAutoscalingController(service,
                new K8sAutomationPolicyNormalizer(new K8sAutomationReadinessProperties()),
                consolidationPlanService, consolidationRunService, spotReadService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        CloudAccount account = new CloudAccount();
        account.setId(7L);
        account.setAwsAccountId("111111111111");
        account.setAccessType("read-write");
        account.setStatus("CONNECTED");
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setCloudAccountId(7L);
        config.setClusterName("prod-eks");
        ClientUserDetails user = new ClientUserDetails("jane.doe", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 1L, 10L, "default");
        ctx = new K8sAutomationRequestContext(user, List.of(account), config);
    }

    private MockHttpServletResponse call(HttpMethod method, String suffix) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.request(method, BASE + suffix)
                        .param("accountId", "111111111111")
                        .requestAttr(K8sAutomationRequestContext.ATTRIBUTE, ctx))
                .andReturn().getResponse();
    }

    @Test
    void getOnAPutOnlyRouteIs405WithoutExceptionText() throws Exception {
        // /consolidation is PUT-only; a GET is an unsupported method. Without the typed handler in
        // GlobalExceptionHandler this returns 500 via the Exception.class catch-all.
        MockHttpServletResponse r = call(HttpMethod.GET, "/consolidation");
        assertThat(r.getStatus()).isEqualTo(405);
        assertThat(r.getContentAsString())
                .doesNotContain("An unexpected error occurred")
                .doesNotContain("Request method 'GET' is not supported");
    }
}
