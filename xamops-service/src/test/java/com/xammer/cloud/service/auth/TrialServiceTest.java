package com.xammer.cloud.service.auth;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TrialService — trial day math, status transitions, extend,
 * convert, and the Redis read-through cache. Validates design Properties 4 & 6.
 */
class TrialServiceTest {

    @Mock private ClientRepository clientRepository;
    @Mock private RedisCacheService redisCacheService;

    private TrialService trialService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        trialService = new TrialService();
        ReflectionTestUtils.setField(trialService, "clientRepository", clientRepository);
        ReflectionTestUtils.setField(trialService, "redisCacheService", redisCacheService);
        ReflectionTestUtils.setField(trialService, "trialDurationDays", 14);
        when(clientRepository.save(any(Client.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── startTrial ────────────────────────────────────────────────────────────

    @Test
    void startTrial_setsTrialPlanAndFourteenDayWindow() {
        Client c = new Client();
        c.setId(1L);

        trialService.startTrial(c);

        assertThat(c.getPlanType()).isEqualTo("TRIAL");
        assertThat(c.getTrialStatus()).isEqualTo("ACTIVE");
        assertThat(c.getTrialStartAt()).isNotNull();
        assertThat(c.getTrialEndAt()).isAfter(LocalDateTime.now().plusDays(13));
        assertThat(c.getTrialEndAt()).isBefore(LocalDateTime.now().plusDays(15));
        verify(redisCacheService).putString(eq("trial:status:1"), eq("ACTIVE"), anyInt());
    }

    // ── daysRemaining ───────────────────────────────────────────────────────────

    @Test
    void daysRemaining_neverNegative_property4() {
        Client c = trialClient(LocalDateTime.now().minusDays(3)); // already past
        assertThat(trialService.daysRemaining(c)).isEqualTo(0);
    }

    @Test
    void daysRemaining_returnsZeroForNonTrial() {
        Client c = new Client();
        c.setPlanType("PAID");
        c.setTrialEndAt(LocalDateTime.now().plusDays(10));
        assertThat(trialService.daysRemaining(c)).isEqualTo(0);
    }

    @Test
    void daysRemaining_countsWholeDays() {
        Client c = trialClient(LocalDateTime.now().plusDays(5).plusHours(2));
        assertThat(trialService.daysRemaining(c)).isEqualTo(5);
    }

    // ── effectiveStatus (lazy expiry) ───────────────────────────────────────────

    @Test
    void effectiveStatus_activeTrialWithFutureEnd_staysActive() {
        Client c = trialClient(LocalDateTime.now().plusDays(3));
        assertThat(trialService.effectiveStatus(c)).isEqualTo("ACTIVE");
    }

    @Test
    void effectiveStatus_pastDueActiveTrial_transitionsToExpired_property4() {
        Client c = trialClient(LocalDateTime.now().minusMinutes(1));

        String status = trialService.effectiveStatus(c);

        assertThat(status).isEqualTo("EXPIRED");
        assertThat(c.getTrialStatus()).isEqualTo("EXPIRED");
        verify(clientRepository).save(c);
    }

    @Test
    void effectiveStatus_nonTrialClient_returnsNone() {
        Client c = new Client();
        c.setPlanType("PAID");
        assertThat(trialService.effectiveStatus(c)).isEqualTo("NONE");
    }

    @Test
    void effectiveStatus_alreadyExpired_staysExpired() {
        Client c = trialClient(LocalDateTime.now().plusDays(2));
        c.setTrialStatus("EXPIRED");
        assertThat(trialService.effectiveStatus(c)).isEqualTo("EXPIRED");
    }

    // ── statusForClient (cache) ─────────────────────────────────────────────────

    @Test
    void statusForClient_returnsCachedValueWithoutDbHit() {
        when(redisCacheService.getString("trial:status:9")).thenReturn("ACTIVE");

        String status = trialService.statusForClient(9L);

        assertThat(status).isEqualTo("ACTIVE");
        verify(clientRepository, never()).findById(anyLong());
    }

    @Test
    void statusForClient_cacheMiss_fallsBackToDb_property6() {
        when(redisCacheService.getString("trial:status:7")).thenReturn(null);
        when(clientRepository.findById(7L)).thenReturn(Optional.of(trialClient(LocalDateTime.now().minusDays(1))));

        String status = trialService.statusForClient(7L);

        assertThat(status).isEqualTo("EXPIRED");
        verify(redisCacheService).putString(eq("trial:status:7"), eq("EXPIRED"), anyInt());
    }

    @Test
    void statusForClient_nullClientId_returnsNone() {
        assertThat(trialService.statusForClient(null)).isEqualTo("NONE");
    }

    // ── extend ──────────────────────────────────────────────────────────────────

    @Test
    void extendTrial_clearsReminderFlagsAndReactivates_property5() {
        Client c = trialClient(LocalDateTime.now().minusDays(1));
        c.setId(3L);
        c.setTrialStatus("EXPIRED");
        c.setReminder5SentAt(LocalDateTime.now());
        c.setFinalDaySentAt(LocalDateTime.now());
        when(clientRepository.findById(3L)).thenReturn(Optional.of(c));

        Client result = trialService.extendTrial(3L, 30);

        assertThat(result.getTrialStatus()).isEqualTo("ACTIVE");
        assertThat(result.getReminder5SentAt()).isNull();
        assertThat(result.getFinalDaySentAt()).isNull();
        assertThat(result.getTrialEndAt()).isAfter(LocalDateTime.now().plusDays(29));
        verify(redisCacheService).putString(eq("trial:status:3"), eq("ACTIVE"), anyInt());
    }

    @Test
    void extendTrial_extendsFromFutureEndWhenStillActive() {
        LocalDateTime futureEnd = LocalDateTime.now().plusDays(4);
        Client c = trialClient(futureEnd);
        c.setId(5L);
        when(clientRepository.findById(5L)).thenReturn(Optional.of(c));

        Client result = trialService.extendTrial(5L, 30);

        // 4 remaining + 30 extension ~= 34 days out
        assertThat(result.getTrialEndAt()).isAfter(LocalDateTime.now().plusDays(33));
    }

    // ── convert ──────────────────────────────────────────────────────────────────

    @Test
    void convertToPaid_removesTrialGating() {
        Client c = trialClient(LocalDateTime.now().plusDays(2));
        c.setId(8L);
        when(clientRepository.findById(8L)).thenReturn(Optional.of(c));

        Client result = trialService.convertToPaid(8L);

        assertThat(result.getPlanType()).isEqualTo("PAID");
        assertThat(result.getTrialStatus()).isEqualTo("CONVERTED");
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService).putString(eq("trial:status:8"), valueCaptor.capture(), anyInt());
        assertThat(valueCaptor.getValue()).isEqualTo("NONE");
    }

    private Client trialClient(LocalDateTime endAt) {
        Client c = new Client();
        c.setId(1L);
        c.setPlanType("TRIAL");
        c.setTrialStatus("ACTIVE");
        c.setTrialStartAt(endAt.minusDays(14));
        c.setTrialEndAt(endAt);
        return c;
    }
}
