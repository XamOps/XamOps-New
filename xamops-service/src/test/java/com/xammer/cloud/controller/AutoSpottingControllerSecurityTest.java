package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AutoSpottingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AutoSpottingControllerSecurityTest {

    @Mock
    private AutoSpottingService autoSpottingService;

    @Mock
    private CloudAccountRepository cloudAccountRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        AutoSpottingController controller = new AutoSpottingController(autoSpottingService, cloudAccountRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(Long clientId, String role) {
        ClientUserDetails principal = new ClientUserDetails(
                "testuser",
                "password",
                List.of(new SimpleGrantedAuthority(role)),
                clientId,
                1L,
                "tenant1"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @Test
    void getSavings_whenAuthorized_returnsOk() throws Exception {
        Long accountId = 123456789012L;
        String accountIdStr = String.valueOf(accountId);
        Long clientId = 1L;

        setSecurityContext(clientId, "ROLE_USER");

        when(cloudAccountRepository.findByAwsAccountIdAndClientId(accountIdStr, clientId))
                .thenReturn(Optional.of(new CloudAccount()));

        mockMvc.perform(get("/api/autospotting/savings/" + accountId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(autoSpottingService, times(1)).getSavingsMetrics(accountId);
    }

    @Test
    void getSavings_whenSuperAdmin_returnsOkWithoutOwnershipCheck() throws Exception {
        Long accountId = 123456789012L;

        setSecurityContext(1L, "ROLE_SUPER_ADMIN");

        mockMvc.perform(get("/api/autospotting/savings/" + accountId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(autoSpottingService, times(1)).getSavingsMetrics(accountId);
        verifyNoInteractions(cloudAccountRepository);
    }

    @Test
    void getSavings_whenUnauthorizedIdor_returnsForbidden() throws Exception {
        Long accountId = 999999999999L;
        String accountIdStr = String.valueOf(accountId);
        Long clientId = 1L;

        setSecurityContext(clientId, "ROLE_USER");

        // Ownership not found in DB
        when(cloudAccountRepository.findByAwsAccountIdAndClientId(accountIdStr, clientId))
                .thenReturn(Optional.empty());
        String paddedId = String.format("%012d", accountId);
        when(cloudAccountRepository.findByAwsAccountIdAndClientId(paddedId, clientId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/autospotting/savings/" + accountId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied"));

        verifyNoInteractions(autoSpottingService);
    }
}
