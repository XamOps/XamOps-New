package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpAgentFleet;
import com.xammer.cloud.xampulse.dto.XpAgentHealth;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.exception.XamPulseExceptionHandler;
import com.xammer.cloud.xampulse.service.agent.AgentFleetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class XamPulseAgentFleetControllerTest {

    private final AgentFleetService fleet = mock(AgentFleetService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new XamPulseAgentFleetController(fleet))
                .setControllerAdvice(new XamPulseExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        ClientUserDetails user = new ClientUserDetails("u", "x", List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 1L, 10L, "t");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsTheFleetForAnAuthorisedAccount() throws Exception {
        when(fleet.fleet(any(), eq(7L))).thenReturn(new XpAgentFleet(new XpAgentFleet.Counts(1, 1, 0, 0, 0),
                List.of(new XpAgentHealth("i-1", null, "ACTIVE", "1.0", null, 10L, 0L, List.of(), List.of()))));
        mvc.perform(get("/api/xamops/xampulse/accounts/7/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.total").value(1))
                .andExpect(jsonPath("$.agents[0].instanceId").value("i-1"))
                .andExpect(jsonPath("$.agents[0].status").value("ACTIVE"));
    }

    @Test
    void aForeignAccountIs404() throws Exception {
        when(fleet.fleet(any(), eq(9L))).thenThrow(XamPulseException.notFound());
        mvc.perform(get("/api/xamops/xampulse/accounts/9/agents"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("XP_NOT_FOUND"));
    }
}
