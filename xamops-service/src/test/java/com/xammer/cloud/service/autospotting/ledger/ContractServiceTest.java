package com.xammer.cloud.service.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.ledger.AutoSpottingContract;
import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerGoLive;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.autospotting.ledger.AutoSpottingContractRepository;
import com.xammer.cloud.repository.autospotting.ledger.SavingsLedgerGoLiveRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.ledger.contract.ContractService;
import com.xammer.cloud.service.autospotting.ledger.contract.ContractService.CreateCommand;
import com.xammer.cloud.service.autospotting.ledger.contract.GoLiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract and go-live rules (plan-phase-15.md task 15.3; design-ledger.md §2.3, §11.4): explicit
 * discount choice (a missing one is never stored as 0), share and cap bounds, no backdating, no
 * overlap, end-only changes, go-live once.
 */
class ContractServiceTest {

    private static final Actor ACTOR = Actor.operator(5L, "ROLE_BILLOPS_ADMIN");

    private AutoSpottingContractRepository contracts;
    private ClientRepository clients;
    private AutoSpottingAuditWriter audit;
    private ContractService service;

    @BeforeEach
    void setUp() {
        contracts = mock(AutoSpottingContractRepository.class);
        clients = mock(ClientRepository.class);
        audit = mock(AutoSpottingAuditWriter.class);
        when(clients.existsById(3L)).thenReturn(true);
        when(contracts.findByClientIdOrderByEffectiveFromDesc(anyLong())).thenReturn(List.of());
        when(contracts.saveAndFlush(any(AutoSpottingContract.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new ContractService(contracts, clients, audit);
    }

    private static CreateCommand cmd(String share, String cap, String declaration, String discount,
                                     Instant from, Instant to) {
        return new CreateCommand(share == null ? null : new BigDecimal(share), cap == null ? null : new BigDecimal(cap),
                "USD", declaration, discount == null ? null : new BigDecimal(discount), from, to, "signed 2026-11");
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AutoSpottingApiException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(422);
            assertThat(e.getCode()).isEqualTo("CHECK_FAILED");
        });
    }

    @Test
    void declaredDiscount_isStored_zeroMeansDeclaredNone() {
        AutoSpottingContract c = service.create(3L, cmd("20", "1000.00", "DECLARED", "0", null, null), ACTOR, "5");
        assertThat(c.getDeclaredDiscountPct()).isEqualByComparingTo("0");
        assertThat(c.getSharePct()).isEqualByComparingTo("20");
        assertThat(c.getMonthlyCap()).isEqualByComparingTo("1000.00");
        assertThat(c.getCapCurrency()).isEqualTo("USD");
        assertThat(c.getCreatedBy()).isEqualTo("5");
        assertThat(c.getEffectiveFrom()).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5));
        verify(audit).write(any());
    }

    @Test
    void notDeclared_isStoredAsNull_neverZero() {
        AutoSpottingContract c = service.create(3L, cmd("12.5", null, "NOT_DECLARED", null, null, null), ACTOR, "5");
        assertThat(c.getDeclaredDiscountPct()).isNull();
        assertThat(c.getMonthlyCap()).isNull();
    }

    @Test
    void theDiscountChoiceIsRequiredAndConsistent() {
        assertInvalid(() -> service.create(3L, cmd("20", null, null, null, null, null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd("20", null, "DECLARED", null, null, null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd("20", null, "DECLARED", "100", null, null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd("20", null, "DECLARED", "-1", null, null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd("20", null, "NOT_DECLARED", "0", null, null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd("20", null, "MAYBE", null, null, null), ACTOR, "5"));
        verify(contracts, never()).saveAndFlush(any());
    }

    @Test
    void shareAndCapBounds() {
        assertInvalid(() -> service.create(3L, cmd("0", null, "NOT_DECLARED", null, null, null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd("100.000001", null, "NOT_DECLARED", null, null, null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd(null, null, "NOT_DECLARED", null, null, null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd("20", "0", "NOT_DECLARED", null, null, null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd("20", "-1", "NOT_DECLARED", null, null, null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd("20", "10.001", "NOT_DECLARED", null, null, null), ACTOR, "5"));
        // 100 percent and a tiny cap are allowed.
        assertThat(service.create(3L, cmd("100", "0.01", "NOT_DECLARED", null, null, null), ACTOR, "5").getSharePct())
                .isEqualByComparingTo("100");
        CreateCommand euro = new CreateCommand(new BigDecimal("20"), null, "EUR", "NOT_DECLARED", null, null, null, "r");
        assertInvalid(() -> service.create(3L, euro, ACTOR, "5"));
    }

    @Test
    void backdatingIsRejected_andFutureDatesAreKept() {
        assertInvalid(() -> service.create(3L, cmd("20", null, "NOT_DECLARED", null,
                Instant.now().minus(Duration.ofHours(1)), null), ACTOR, "5"));
        assertInvalid(() -> service.create(3L, cmd("20", null, "NOT_DECLARED", null, null,
                Instant.now().minus(Duration.ofHours(1))), ACTOR, "5"));
        Instant from = Instant.now().plus(Duration.ofDays(10));
        assertInvalid(() -> service.create(3L, cmd("20", null, "NOT_DECLARED", null, from, from), ACTOR, "5"));
        AutoSpottingContract c = service.create(3L, cmd("20", null, "NOT_DECLARED", null, from,
                from.plus(Duration.ofDays(30))), ACTOR, "5");
        assertThat(c.getEffectiveFrom()).isEqualTo(from);
    }

    @Test
    void overlap_is409_andAdjacentRangesAreFine() {
        Instant from = Instant.now().plus(Duration.ofDays(10));
        Instant to = from.plus(Duration.ofDays(30));
        AutoSpottingContract existing = new AutoSpottingContract();
        existing.setId(UUID.randomUUID());
        existing.setClientId(3L);
        existing.setEffectiveFrom(from);
        existing.setEffectiveTo(to);
        when(contracts.findByClientIdOrderByEffectiveFromDesc(3L)).thenReturn(List.of(existing));
        assertThatThrownBy(() -> service.create(3L, cmd("20", null, "NOT_DECLARED", null,
                from.plus(Duration.ofDays(5)), null), ACTOR, "5"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("CONTRACT_OVERLAP");
                });
        // Open-ended new contract that starts before the existing one ends.
        assertThatThrownBy(() -> service.create(3L, cmd("20", null, "NOT_DECLARED", null,
                from.minus(Duration.ofDays(2)), null), ACTOR, "5")).isInstanceOf(AutoSpottingApiException.class);
        // Starting exactly where the existing one ends is allowed (half-open ranges).
        assertThat(service.create(3L, cmd("20", null, "NOT_DECLARED", null, to, null), ACTOR, "5")).isNotNull();
        // Ending exactly where the existing one starts is allowed too.
        assertThat(service.create(3L, cmd("20", null, "NOT_DECLARED", null, Instant.now().plusSeconds(60), from),
                ACTOR, "5")).isNotNull();
    }

    @Test
    void aRacingTriggerRejection_isMappedToTheSameConflict() {
        when(contracts.saveAndFlush(any(AutoSpottingContract.class))).thenThrow(new org.springframework.dao
                .DataIntegrityViolationException("x", new RuntimeException("ERROR: contract overlaps another contract of the client")));
        assertThatThrownBy(() -> service.create(3L, cmd("20", null, "NOT_DECLARED", null, null, null), ACTOR, "5"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getCode()).isEqualTo("CONTRACT_OVERLAP"));
    }

    @Test
    void reasonAndClientAreChecked() {
        CreateCommand noReason = new CreateCommand(new BigDecimal("20"), null, "USD", "NOT_DECLARED", null, null, null, " ");
        assertInvalid(() -> service.create(3L, noReason, ACTOR, "5"));
        CreateCommand longReason = new CreateCommand(new BigDecimal("20"), null, "USD", "NOT_DECLARED", null, null, null,
                "x".repeat(501));
        assertInvalid(() -> service.create(3L, longReason, ACTOR, "5"));
        assertThatThrownBy(() -> service.create(99L, cmd("20", null, "NOT_DECLARED", null, null, null), ACTOR, "5"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    @Test
    void ending_onlyEarlier_andNeverBackdated() {
        Instant from = Instant.now().plus(Duration.ofDays(1));
        AutoSpottingContract c = new AutoSpottingContract();
        c.setId(UUID.randomUUID());
        c.setClientId(3L);
        c.setSharePct(new BigDecimal("20"));
        c.setEffectiveFrom(from);
        c.setEffectiveTo(from.plus(Duration.ofDays(60)));
        when(contracts.findById(c.getId())).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.end(c.getId(), from.plus(Duration.ofDays(90)), ACTOR, "r"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getCode()).isEqualTo("CONTRACT_CANNOT_EXTEND"));
        assertInvalid(() -> service.end(c.getId(), Instant.now().minus(Duration.ofDays(1)), ACTOR, "r"));
        assertInvalid(() -> service.end(c.getId(), from, ACTOR, "r"));
        assertInvalid(() -> service.end(c.getId(), from.plus(Duration.ofDays(10)), ACTOR, ""));
        Instant newEnd = from.plus(Duration.ofDays(20));
        assertThat(service.end(c.getId(), newEnd, ACTOR, "customer left").getEffectiveTo()).isEqualTo(newEnd);
        // The terms are untouched.
        assertThat(c.getSharePct()).isEqualByComparingTo("20");
        assertThatThrownBy(() -> service.end(UUID.randomUUID(), null, ACTOR, "r"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    // ── go-live ─────────────────────────────────────────────────────────────

    @Test
    void goLive_isSetOnce() {
        SavingsLedgerGoLiveRepository repo = mock(SavingsLedgerGoLiveRepository.class);
        when(repo.save(any(SavingsLedgerGoLive.class))).thenAnswer(inv -> inv.getArgument(0));
        GoLiveService goLive = new GoLiveService(repo, audit);
        SavingsLedgerGoLive saved = goLive.set(Provider.AWS, null, ACTOR, "5", "first lift");
        assertThat(saved.getProvider()).isEqualTo("AWS");
        assertThat(saved.getGoLiveAt()).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5));
        ArgumentCaptor<SavingsLedgerGoLive> captor = ArgumentCaptor.forClass(SavingsLedgerGoLive.class);
        verify(repo).save(captor.capture());
        when(repo.existsById("AWS")).thenReturn(true);
        assertThatThrownBy(() -> goLive.set(Provider.AWS, null, ACTOR, "5", "again"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("GOLIVE_ALREADY_SET");
                });
    }

    @Test
    void goLive_refusesThePast_aBlankReason_andAnUnknownProvider() {
        SavingsLedgerGoLiveRepository repo = mock(SavingsLedgerGoLiveRepository.class);
        GoLiveService goLive = new GoLiveService(repo, audit);
        assertInvalid(() -> goLive.set(Provider.GCP, Instant.now().minus(Duration.ofDays(1)), ACTOR, "5", "r"));
        assertInvalid(() -> goLive.set(Provider.GCP, null, ACTOR, "5", " "));
        verify(repo, never()).save(any());
        assertThat(GoLiveService.parseProvider("gcp")).isEqualTo(Provider.GCP);
        assertThatThrownBy(() -> GoLiveService.parseProvider("azure"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }
}
