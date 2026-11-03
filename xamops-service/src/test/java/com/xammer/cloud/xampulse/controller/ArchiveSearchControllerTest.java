package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpArchiveEstimate;
import com.xammer.cloud.xampulse.exception.XamPulseExceptionHandler;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.archive.ArchiveSearchService;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ArchiveSearchControllerTest {

    private static final long MINE = 100L;
    private static final long FOREIGN = 200L;

    private MockMvc mvc;
    private ArchiveSearchService search;

    @BeforeEach
    void setUp() {
        CloudAccountAccessService access = mock(CloudAccountAccessService.class);
        CloudAccount mine = new CloudAccount();
        mine.setId(MINE);
        mine.setAwsAccountId("123456789012");
        mine.setProvider("AWS");
        when(access.requireAccess(any(), eq(MINE))).thenReturn(mine);
        when(access.requireAccess(any(), eq(FOREIGN))).thenThrow(new AccessDeniedException("denied"));
        XamPulseAccessGuard guard = new XamPulseAccessGuard(access, mock(CloudAccountRepository.class));

        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        XpAccountConfig cfg = new XpAccountConfig();
        cfg.setCloudAccountId(MINE);
        when(configs.findByCloudAccountId(MINE)).thenReturn(Optional.of(cfg));

        search = mock(ArchiveSearchService.class);
        when(search.estimate(any(), eq("123456789012"), any(), any(), any()))
                .thenReturn(new XpArchiveEstimate(2048L, 3L, 0.12, List.of("STANDARD"), "ESTIMATE"));

        mvc = MockMvcBuilders.standaloneSetup(new XamPulseArchiveSearchController(guard, configs, search))
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

    private static String scope(long... ids) {
        Instant to = Instant.now();
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            b.append(i == 0 ? "" : ",").append(ids[i]);
        }
        b.append("]");
        return "{\"accountIds\":" + b + ",\"from\":\"" + to.minus(Duration.ofHours(1)) + "\",\"to\":\"" + to + "\"}";
    }

    @Test
    void authorisedSingleAccountReturnsTheEstimate() throws Exception {
        mvc.perform(post("/api/xamops/xampulse/archive-searches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":" + scope(MINE) + ",\"query\":\"error\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bytes").value(2048))
                .andExpect(jsonPath("$.requests").value(3))
                .andExpect(jsonPath("$.state").value("ESTIMATE"));
    }

    @Test
    void foreignAccountIsTheUniformNotFound() throws Exception {
        mvc.perform(post("/api/xamops/xampulse/archive-searches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":" + scope(FOREIGN) + ",\"query\":\"error\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("XP_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Not found."));
        verifyNoInteractions(search);
    }

    @Test
    void multipleAccountsIsRejectedAsInvalidRequest() throws Exception {
        mvc.perform(post("/api/xamops/xampulse/archive-searches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":" + scope(MINE, FOREIGN) + ",\"query\":\"error\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("XP_INVALID_REQUEST"));
        verifyNoInteractions(search);
    }
}
