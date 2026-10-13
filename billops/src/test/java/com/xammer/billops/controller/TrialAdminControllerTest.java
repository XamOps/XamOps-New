package com.xammer.billops.controller;

import com.xammer.billops.domain.Client;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain JUnit 5 + Mockito (no Spring context, so no Redis or DB is needed) coverage of the
 * trial admin endpoints: the list row shape and the includeConverted opt-in, plus the
 * extend/convert side effects including the trial:status:{id} cache eviction.
 */
class TrialAdminControllerTest {

    private ClientRepository clientRepository;
    private RedisCacheService redisCacheService;
    private TrialAdminController controller;

    @BeforeEach
    void setUp() {
        clientRepository = mock(ClientRepository.class);
        redisCacheService = mock(RedisCacheService.class);
        controller = new TrialAdminController(clientRepository, redisCacheService);
    }

    private Client client(Long id, String name, String planType, String trialStatus, LocalDateTime endAt) {
        Client c = new Client(name);
        c.setId(id);
        c.setEmail(name + "@example.com");
        c.setPlanType(planType);
        c.setTrialStatus(trialStatus);
        c.setTrialEndAt(endAt);
        return c;
    }

    @Test
    void listTrialsWithoutIncludeConvertedKeepsLegacyQueryAndExposesPlanType() {
        when(clientRepository.findByPlanType("TRIAL")).thenReturn(List.of(
                client(1L, "acme", "TRIAL", "ACTIVE", LocalDateTime.now().plusDays(5))));

        ResponseEntity<List<Map<String, Object>>> response = controller.listTrials(false);

        verify(clientRepository).findByPlanType("TRIAL");
        verify(clientRepository, never()).findTrialOrFormerTrial(anyString());
        List<Map<String, Object>> rows = response.getBody();
        assertThat(rows).hasSize(1);
        assertThat(rows).allSatisfy(row -> assertThat(row).containsKey("planType"));
        assertThat(rows.get(0).get("planType")).isEqualTo("TRIAL");
        assertThat(rows.get(0).get("clientId")).isEqualTo(1L);
    }

    @Test
    void listTrialsWithIncludeConvertedReturnsConvertedTenants() {
        when(clientRepository.findTrialOrFormerTrial("TRIAL")).thenReturn(List.of(
                client(1L, "acme", "TRIAL", "ACTIVE", LocalDateTime.now().plusDays(5)),
                client(135L, "Nowageeks", "PAID", "CONVERTED", LocalDateTime.now().minusDays(4))));

        ResponseEntity<List<Map<String, Object>>> response = controller.listTrials(true);

        verify(clientRepository).findTrialOrFormerTrial("TRIAL");
        verify(clientRepository, never()).findByPlanType(anyString());
        List<Map<String, Object>> rows = response.getBody();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row).containsKey("planType"));
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("clientId")).isEqualTo(135L);
            assertThat(row.get("planType")).isEqualTo("PAID");
            assertThat(row.get("trialStatus")).isEqualTo("CONVERTED");
        });
    }

    @Test
    void extendOnLiveTrialAddsDaysToExistingEndDateAndEvictsCache() {
        LocalDateTime end = LocalDateTime.now().plusDays(3);
        Client c = client(7L, "live", "TRIAL", "EXPIRED", end);
        c.setReminder5SentAt(LocalDateTime.now().minusDays(1));
        c.setFinalDaySentAt(LocalDateTime.now().minusHours(2));
        when(clientRepository.findById(7L)).thenReturn(Optional.of(c));

        ResponseEntity<?> response = controller.extend(7L, Map.of("days", "30"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<Client> saved = ArgumentCaptor.forClass(Client.class);
        verify(clientRepository).save(saved.capture());
        Client s = saved.getValue();
        assertThat(s.getTrialStatus()).isEqualTo("ACTIVE");
        assertThat(s.getTrialEndAt()).isEqualTo(end.plusDays(30));
        assertThat(s.getReminder5SentAt()).isNull();
        assertThat(s.getFinalDaySentAt()).isNull();
        verify(redisCacheService).evict("trial:status:7");
    }

    @Test
    void extendOnLapsedTrialBasesNewEndDateOnNow() {
        LocalDateTime staleEnd = LocalDateTime.now().minusDays(20);
        Client c = client(8L, "lapsed", "TRIAL", "EXPIRED", staleEnd);
        when(clientRepository.findById(8L)).thenReturn(Optional.of(c));

        LocalDateTime before = LocalDateTime.now();
        controller.extend(8L, Map.of("days", "30"));
        LocalDateTime after = LocalDateTime.now();

        verify(clientRepository).save(c);
        assertThat(c.getTrialEndAt()).isAfter(before.plusDays(29));
        assertThat(c.getTrialEndAt()).isBeforeOrEqualTo(after.plusDays(30));
        assertThat(c.getTrialEndAt()).isNotEqualTo(staleEnd.plusDays(30));
    }

    @Test
    void extendWithZeroDaysIsRejectedWithoutSaveOrEvict() {
        Client c = client(9L, "zero", "TRIAL", "EXPIRED", LocalDateTime.now().plusDays(1));
        when(clientRepository.findById(9L)).thenReturn(Optional.of(c));

        ResponseEntity<?> response = controller.extend(9L, Map.of("days", "0"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(clientRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(redisCacheService, never()).evict(anyString());
    }

    @Test
    void convertMarksClientPaidAndEvictsCache() {
        Client c = client(135L, "Nowageeks", "TRIAL", "EXPIRED", LocalDateTime.now().minusDays(4));
        when(clientRepository.findById(135L)).thenReturn(Optional.of(c));

        ResponseEntity<?> response = controller.convert(135L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(c.getPlanType()).isEqualTo("PAID");
        assertThat(c.getTrialStatus()).isEqualTo("CONVERTED");
        verify(clientRepository).save(c);
        verify(redisCacheService).evict("trial:status:135");
    }
}
