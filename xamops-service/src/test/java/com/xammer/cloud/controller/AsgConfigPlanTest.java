package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.AutoSpottingService;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The FR-CMP-04 "ASGConfigPage saving with apply=false" regression (ux §9.8, AC-CMP-06;
 * plan-phase-11.md task 11.5). The DB-only save keeps working and mutates nothing in the cloud; the
 * apply=true path returns 409 {@code PLAN_REQUIRED} and makes no SDK call (AC-PLN-07).
 */
class AsgConfigPlanTest {

    private static final String AWS_ID = "012345678901";
    private static final String BASE = "/api/autospotting";
    private static final String ASG = "?region=us-east-1&asgName=web";

    private AutoSpottingService service;
    private CloudAccountAccessService accessService;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        service = mock(AutoSpottingService.class);
        accessService = mock(CloudAccountAccessService.class);
        account = new CloudAccount();
        account.setId(7L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);
        when(accessService.requireAwsAccess(any(), eq(AWS_ID))).thenReturn(account);

        ClientUserDetails principal = new ClientUserDetails("user@example.com", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), 10L, 500L, "tenant-a");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        AutoSpottingController controller = new AutoSpottingController(service, accessService,
                new AutoSpottingFeatureFlags(false, false, false, false),
                mock(com.xammer.cloud.service.autospotting.core.LegacyDisableGuard.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void saveWithoutApplyMutatesNothing_instanceTypes() throws Exception {
        mvc().perform(json(put(BASE + "/asgs/" + AWS_ID + "/instance-types" + ASG + "&apply=false"),
                        "{\"instanceTypes\":[\"t3.micro\",\"t3a.micro\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false));
        // The DB-only save is called with apply=false; no apply-true SDK path runs (ux §9.8).
        verify(service).saveInstanceTypeConfig(account, "us-east-1", "web",
                List.of("t3.micro", "t3a.micro"), false);
    }

    @Test
    void saveWithoutApplyMutatesNothing_spotConfig() throws Exception {
        mvc().perform(json(put(BASE + "/asgs/" + AWS_ID + "/spot-config" + ASG + "&apply=false"),
                        "{\"spotPercentage\":75}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false));
        verify(service).saveSpotConfig(account, "us-east-1", "web", 75, null, false);
    }

    @Test
    void applyTrue_isPlanRequired_andNeverSaves() throws Exception {
        mvc().perform(json(put(BASE + "/asgs/" + AWS_ID + "/spot-config" + ASG + "&apply=true"),
                        "{\"spotPercentage\":75}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.PLAN_REQUIRED));
        verify(service, never()).saveSpotConfig(any(), any(), any(), any(), any(), eq(true));
    }
}
