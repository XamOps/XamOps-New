package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.controller.azure.AzureCloudsitterController;
import com.xammer.cloud.controller.gcp.GcpCloudsitterController;
import com.xammer.cloud.domain.CloudsitterPolicy;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.AzureCloudsitterAssignmentRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CloudsitterAssignmentRepository;
import com.xammer.cloud.repository.CloudsitterPolicyRepository;
import com.xammer.cloud.repository.GcpCloudsitterAssignmentRepository;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.AwsPricingService;
import com.xammer.cloud.service.CloudsitterEmailBuilder;
import com.xammer.cloud.service.CloudsitterService;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.TenantService;
import com.xammer.cloud.service.azure.AzureClientProvider;
import com.xammer.cloud.service.azure.AzureCloudsitterService;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import com.xammer.cloud.service.gcp.GcpCloudsitterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC 19 at the API (design D11): Cloudsitter create and update refuse an OID-shaped
 * {@code scheduleConfig} with a 400 for all three providers, before the policy repository is
 * touched. Standalone MockMvc over the real controllers and services, with mocked dependencies and
 * the application's {@link GlobalExceptionHandler}.
 */
class CloudsitterOidRejectionTest {

    private static final String OID_SHAPED = "{\"name\":\"Weekdays\",\"type\":\"SCHEDULE\",\"timeZone\":\"UTC\","
            + "\"scheduleConfig\":\"16842\"}";
    private static final String SCHEDULE = "{\"name\":\"Weekdays\",\"type\":\"SCHEDULE\",\"timeZone\":\"UTC\","
            + "\"scheduleConfig\":\"{\\\"MONDAY\\\":[9,10,11]}\"}";

    private CloudsitterPolicyRepository policyRepo;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        policyRepo = mock(CloudsitterPolicyRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        CloudAccountRepository accountRepo = mock(CloudAccountRepository.class);
        TenantService tenantService = mock(TenantService.class);
        RedisCacheService cache = mock(RedisCacheService.class);

        CloudsitterService aws = new CloudsitterService(policyRepo, mock(CloudsitterAssignmentRepository.class),
                mock(AwsClientProvider.class), accountRepo, objectMapper, tenantService, mock(AwsPricingService.class),
                mock(EmailService.class), mock(CloudsitterEmailBuilder.class));
        AzureCloudsitterService azure = new AzureCloudsitterService(mock(AzureCloudsitterAssignmentRepository.class),
                policyRepo, accountRepo, mock(AzureClientProvider.class), cache, objectMapper, tenantService);
        GcpCloudsitterService gcp = new GcpCloudsitterService(mock(GcpCloudsitterAssignmentRepository.class),
                policyRepo, accountRepo, mock(GcpClientProvider.class), cache, objectMapper, tenantService);

        mockMvc = MockMvcBuilders.standaloneSetup(new CloudsitterController(aws), new AzureCloudsitterController(azure),
                        new GcpCloudsitterController(gcp))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"xamops", "azure", "gcp"})
    void createWithAnOidShapedScheduleConfigIs400AndTouchesNoPolicy(String provider) throws Exception {
        mockMvc.perform(json(post("/api/" + provider + "/cloudsitter/policies"), OID_SHAPED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("scheduleConfig")));

        verify(policyRepo, never()).save(any());
        verify(policyRepo, never()).findById(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"xamops", "azure", "gcp"})
    void updateWithAnOidShapedScheduleConfigIs400AndTouchesNoPolicy(String provider) throws Exception {
        mockMvc.perform(json(put("/api/" + provider + "/cloudsitter/policies/1"), OID_SHAPED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("scheduleConfig")));

        verify(policyRepo, never()).save(any());
        verify(policyRepo, never()).findById(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"xamops", "azure", "gcp"})
    void aJsonScheduleConfigIsStillAccepted(String provider) throws Exception {
        when(policyRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CloudsitterPolicy existing = new CloudsitterPolicy();
        existing.setId(1L);
        existing.setName("Before");
        when(policyRepo.findById(anyLong())).thenReturn(Optional.of(existing));

        mockMvc.perform(json(post("/api/" + provider + "/cloudsitter/policies"), SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleJson").value("{\"MONDAY\":[9,10,11]}"));
        mockMvc.perform(json(put("/api/" + provider + "/cloudsitter/policies/1"), SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Weekdays"));
    }

    private static RequestBuilder json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                       String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
