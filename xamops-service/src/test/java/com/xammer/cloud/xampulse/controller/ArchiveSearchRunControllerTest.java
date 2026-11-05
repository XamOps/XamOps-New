package com.xammer.cloud.xampulse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpArchiveEstimate;
import com.xammer.cloud.xampulse.dto.XpArchiveSearchJob;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.exception.XamPulseExceptionHandler;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.archive.ArchiveSearchJobStore;
import com.xammer.cloud.xampulse.service.archive.ArchiveSearchJobStoreTest;
import com.xammer.cloud.xampulse.service.archive.ArchiveSearchRunner;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.platform.IdempotencyService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ArchiveSearchRunControllerTest {

    private static final long MINE = 100L;
    private static final long FOREIGN = 200L;
    private static final String PATH = "/api/xamops/xampulse/archive-searches";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ArchiveSearchJobStoreTest.MemoryStore memory = new ArchiveSearchJobStoreTest.MemoryStore();
    private final ArchiveSearchJobStore jobs = new ArchiveSearchJobStore(memory, mapper);
    private ArchiveSearchRunner runner;
    private MockMvc mvc;

    private void signIn(long id, String role) {
        ClientUserDetails user = new ClientUserDetails("u" + id, "x", List.of(new SimpleGrantedAuthority(role)), 1L, id, "t");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

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
        runner = mock(ArchiveSearchRunner.class);
        IdempotencyService idem = new IdempotencyService(memory, mapper);
        mvc = MockMvcBuilders.standaloneSetup(new XamPulseArchiveSearchRunController(guard, configs, runner, jobs, idem,
                        new AuditService(mock(com.xammer.cloud.xampulse.repository.XpAuditRepository.class))))
                .setControllerAdvice(new XamPulseExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        signIn(10L, "ROLE_XAMOPS");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static String body(long account, long confirmed) {
        Instant to = Instant.now();
        return "{\"scope\":{\"accountIds\":[" + account + "],\"from\":\"" + to.minus(Duration.ofHours(3)) + "\",\"to\":\"" + to
                + "\"},\"query\":\"error\",\"confirmedBytes\":" + confirmed + "}";
    }

    private XpArchiveSearchJob job(String id, String state, boolean withRows) {
        return new XpArchiveSearchJob(id, state, new XpArchiveEstimate(10L, 1L, 0.0, List.of("STANDARD"), "ESTIMATE"), 1, 1,
                withRows ? List.of() : null, withRows ? Boolean.FALSE : null, null, Instant.parse("2026-11-02T10:00:00Z"), null);
    }

    private String savedJob(long owner, String state) {
        String id = jobs.newId();
        jobs.save(new ArchiveSearchJobStore.Record(owner, MINE, job(id, state, "DONE".equals(state))));
        return id;
    }

    @Test
    void runIsAdminOnlyByAnnotation() throws Exception {
        var m = XamPulseArchiveSearchRunController.class.getMethod("run", ClientUserDetails.class,
                com.xammer.cloud.xampulse.dto.XpArchiveSearchRunRequest.class, String.class, jakarta.servlet.http.HttpServletRequest.class);
        assertEquals(XamPulseRoles.ADMIN, m.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
        for (String name : new String[] {"get", "cancel"}) {
            var other = XamPulseArchiveSearchRunController.class.getMethod(name, ClientUserDetails.class, String.class);
            assertEquals(XamPulseRoles.VIEWER, other.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
        }
    }

    @Test
    void runAnswers202WithTheQueuedJob() throws Exception {
        when(runner.start(eq(10L), any(), any(), eq("123456789012"), any(), eq("error"), eq(500L))).thenReturn(job("j1", "QUEUED", false));
        mvc.perform(post(PATH + "/run").contentType(MediaType.APPLICATION_JSON).content(body(MINE, 500)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("j1"))
                .andExpect(jsonPath("$.state").value("QUEUED"));
    }

    @Test
    void aForeignAccountIs404AndTheRunnerIsNeverCalled() throws Exception {
        mvc.perform(post(PATH + "/run").contentType(MediaType.APPLICATION_JSON).content(body(FOREIGN, 500)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(runner);
    }

    @Test
    void aStaleEstimateIs409() throws Exception {
        when(runner.start(anyLong(), any(), any(), any(), any(), any(), anyLong())).thenThrow(XamPulseException.estimateChanged());
        mvc.perform(post(PATH + "/run").contentType(MediaType.APPLICATION_JSON).content(body(MINE, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("XP_ARCHIVE_ESTIMATE_CHANGED"));
    }

    @Test
    void aReplayedIdempotencyKeyReturnsTheSameJobAndRunsOnce() throws Exception {
        when(runner.start(anyLong(), any(), any(), any(), any(), any(), anyLong())).thenReturn(job("j1", "QUEUED", false));
        String b = body(MINE, 500);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(PATH + "/run").header("Idempotency-Key", "run-key-12345").contentType(MediaType.APPLICATION_JSON).content(b))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.id").value("j1"));
        }
        verify(runner, times(1)).start(anyLong(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void pollByTheOwnerReturnsProgressWithoutRowsUntilDone() throws Exception {
        String running = savedJob(10L, "RUNNING");
        mvc.perform(get(PATH + "/" + running)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.rows").doesNotExist());
        String done = savedJob(10L, "DONE");
        mvc.perform(get(PATH + "/" + done)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DONE"))
                .andExpect(jsonPath("$.rows").isArray());
    }

    @Test
    void anotherUserPollingOrCancellingGets404() throws Exception {
        String id = savedJob(99L, "RUNNING");
        signIn(10L, "ROLE_XAMOPS");
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete(PATH + "/" + id)).andExpect(status().isNotFound());
        verify(runner, never()).cancel(any());
    }

    @Test
    void theOwnerMayCancelTheirOwnJob() throws Exception {
        String id = savedJob(10L, "RUNNING");
        when(runner.cancel(any())).thenReturn(job(id, "CANCELLED", false));
        mvc.perform(delete(PATH + "/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test
    void unknownAndMalformedIdsAre404() throws Exception {
        mvc.perform(get(PATH + "/" + jobs.newId())).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/not-an-id")).andExpect(status().isNotFound());
    }
}
