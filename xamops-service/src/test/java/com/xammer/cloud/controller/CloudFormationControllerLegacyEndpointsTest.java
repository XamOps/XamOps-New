package com.xammer.cloud.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.StsException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The legacy DynamoDB registry endpoints had no tenant or role checks and no callers, so they were
 * deleted. They must stay unmapped (404, no AWS call), while the quick-create links and the host
 * stack health probe keep working.
 */
class CloudFormationControllerLegacyEndpointsTest {

    private static final String BASE = "/api/cloudformation";

    private CloudFormationClient cloudFormationClient;
    private StsClient stsClient;
    private CloudFormationController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        cloudFormationClient = mock(CloudFormationClient.class);
        stsClient = mock(StsClient.class);
        controller = new CloudFormationController(cloudFormationClient, stsClient);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void syncAccount_isNotMapped() throws Exception {
        mvc.perform(post(BASE + "/sync-account").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(cloudFormationClient, stsClient);
    }

    @Test
    void listAccounts_isNotMapped() throws Exception {
        mvc.perform(get(BASE + "/accounts"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(cloudFormationClient, stsClient);
    }

    @Test
    void deleteAccount_isNotMapped() throws Exception {
        mvc.perform(delete(BASE + "/accounts/123456789012"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(cloudFormationClient, stsClient);
    }

    @Test
    void mainStackInfo_isNotMapped() throws Exception {
        mvc.perform(get(BASE + "/main-stack-info"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(cloudFormationClient, stsClient);
    }

    @Test
    void deployUrl_stillReturnsQuickCreateLink() throws Exception {
        mvc.perform(get(BASE + "/deploy-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty())
                .andExpect(jsonPath("$.url").value(startsWith("https://console.aws.amazon.com/cloudformation/home")))
                .andExpect(jsonPath("$.deployRegion").value("us-east-1"));
        verifyNoInteractions(cloudFormationClient, stsClient);
    }

    @Test
    void curDeployUrl_stillMapped() throws Exception {
        ReflectionTestUtils.setField(controller, "curTemplateUrl",
                "https://templates.example.com/xamops-cur-report.yml");

        mvc.perform(get(BASE + "/cur-deploy-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString("xamops-cur-report.yml")))
                .andExpect(jsonPath("$.deployRegion").value("us-east-1"));
        verifyNoInteractions(cloudFormationClient, stsClient);
    }

    @Test
    void health_stillMapped() throws Exception {
        // Host role unavailable -> the controller falls back to the injected client (a mock here),
        // so no real AWS call is made.
        when(stsClient.assumeRole(any(AssumeRoleRequest.class)))
                .thenThrow(StsException.builder().message("no host role in tests").build());
        when(cloudFormationClient.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(DescribeStacksResponse.builder()
                        .stacks(Stack.builder().stackStatus(StackStatus.CREATE_COMPLETE).build())
                        .build());

        mvc.perform(get(BASE + "/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"))
                .andExpect(jsonPath("$.mainStack").value("autospotting"));
    }
}
