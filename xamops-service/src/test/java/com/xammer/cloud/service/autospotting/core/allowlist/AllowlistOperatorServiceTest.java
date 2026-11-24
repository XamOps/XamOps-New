package com.xammer.cloud.service.autospotting.core.allowlist;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.AutoSpottingAllowlistEntry;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.ledger.AutoSpottingContract;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository;
import com.xammer.cloud.repository.autospotting.ledger.AutoSpottingContractRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.allowlist.AllowlistOperatorService.AllowlistView;
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
 * The allowlist billing basis (design-core.md §6.1, DC-11; ux §9.2 rows): CONTRACT only while the
 * client has a contract in force (its id is recorded), NO_FEE only with a reason, anything else 409
 * {@code ALLOWLIST_NEEDS_BILLING_BASIS}; each change audited; removal deletes the row.
 */
class AllowlistOperatorServiceTest {

    private static final Actor ACTOR = Actor.operator(11L, "ROLE_SUPER_ADMIN");

    private AutoSpottingAllowlistEntryRepository allowlist;
    private AutoSpottingContractRepository contracts;
    private CloudAccountRepository accounts;
    private AutoSpottingAuditWriter audit;
    private AllowlistOperatorService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        allowlist = mock(AutoSpottingAllowlistEntryRepository.class);
        contracts = mock(AutoSpottingContractRepository.class);
        accounts = mock(CloudAccountRepository.class);
        audit = mock(AutoSpottingAuditWriter.class);
        Client client = new Client();
        client.setId(3L);
        account = new CloudAccount();
        account.setId(7L);
        account.setAccountName("prod");
        account.setProvider("AWS");
        account.setClient(client);
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(allowlist.save(any(AutoSpottingAllowlistEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(contracts.findInForce(anyLong(), any())).thenReturn(List.of());
        service = new AllowlistOperatorService(allowlist, contracts, accounts, audit);
    }

    private AutoSpottingContract contractInForce() {
        AutoSpottingContract c = new AutoSpottingContract();
        c.setId(UUID.randomUUID());
        c.setClientId(3L);
        c.setSharePct(new BigDecimal("20"));
        c.setEffectiveFrom(Instant.now().minus(Duration.ofDays(1)));
        when(contracts.findInForce(anyLong(), any())).thenReturn(List.of(c));
        when(contracts.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private static void assertNeedsBasis(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AutoSpottingApiException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(409);
            assertThat(e.getCode()).isEqualTo("ALLOWLIST_NEEDS_BILLING_BASIS");
        });
    }

    @Test
    void contractBasis_withAContractInForce_recordsItsId() {
        AutoSpottingContract c = contractInForce();
        AllowlistView view = service.add(7L, "CONTRACT", "Trata Group pilot", ACTOR, "11");
        assertThat(view.billingBasis()).isEqualTo("CONTRACT");
        assertThat(view.contractId()).isEqualTo(c.getId());
        assertThat(view.contractInForce()).isTrue();
        assertThat(view.provider()).isEqualTo("AWS");
        assertThat(view.clientId()).isEqualTo(3L);
        verify(audit).write(any());
    }

    @Test
    void contractBasis_withoutAContract_is409() {
        assertNeedsBasis(() -> service.add(7L, "CONTRACT", "pilot", ACTOR, "11"));
        verify(allowlist, never()).save(any());
        verify(audit, never()).write(any());
    }

    @Test
    void noFeeBasis_needsAReason_andRecordsNoContract() {
        assertNeedsBasis(() -> service.add(7L, "NO_FEE", null, ACTOR, "11"));
        assertNeedsBasis(() -> service.add(7L, "NO_FEE", "   ", ACTOR, "11"));
        // NO_FEE ignores a contract in force: the account's groups never bill.
        contractInForce();
        AllowlistView view = service.add(7L, "NO_FEE", "XamOps test project", ACTOR, "11");
        assertThat(view.billingBasis()).isEqualTo("NO_FEE");
        assertThat(view.contractId()).isNull();
        assertThat(view.contractInForce()).isFalse();
    }

    @Test
    void anyOtherBasis_is409() {
        assertNeedsBasis(() -> service.add(7L, null, "reason", ACTOR, "11"));
        assertNeedsBasis(() -> service.add(7L, "FREE", "reason", ACTOR, "11"));
        assertNeedsBasis(() -> service.add(7L, "contract", "reason", ACTOR, "11"));
    }

    @Test
    void reasonLengthAndAccount_areChecked() {
        contractInForce();
        assertThatThrownBy(() -> service.add(7L, "CONTRACT", "x".repeat(501), ACTOR, "11"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
        assertThatThrownBy(() -> service.add(7L, "CONTRACT", "", ACTOR, "11"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
        assertThatThrownBy(() -> service.add(8L, "NO_FEE", "r", ACTOR, "11"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        account.setProvider("Azure");
        assertThatThrownBy(() -> service.add(7L, "NO_FEE", "r", ACTOR, "11"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
    }

    @Test
    void changingTheBasisOfAnExistingRow_isCheckedAgainAndAudited() {
        AutoSpottingAllowlistEntry existing = new AutoSpottingAllowlistEntry();
        existing.setCloudAccountId(7L);
        existing.setProvider(Provider.AWS);
        existing.setBillingBasis("NO_FEE");
        existing.setReason("sandbox");
        existing.setAddedBy("1");
        existing.setAddedAt(Instant.now());
        when(allowlist.findById(7L)).thenReturn(Optional.of(existing));
        // Moving to CONTRACT needs a contract in force.
        assertNeedsBasis(() -> service.add(7L, "CONTRACT", "signed", ACTOR, "11"));
        AutoSpottingContract c = contractInForce();
        AllowlistView view = service.add(7L, "CONTRACT", "signed", ACTOR, "11");
        assertThat(view.contractId()).isEqualTo(c.getId());
        ArgumentCaptor<AutoSpottingAuditWriter.AuditRecord> captor = ArgumentCaptor.forClass(AutoSpottingAuditWriter.AuditRecord.class);
        verify(audit).write(captor.capture());
        assertThat(captor.getValue().action()).startsWith("ALLOWLIST_CHANGE");
        assertThat(captor.getValue().beforeState()).contains("NO_FEE");
        assertThat(captor.getValue().afterState()).contains("CONTRACT");
    }

    @Test
    void options_offerTheContractBasisOnlyWhileOneIsInForce() {
        assertThat(service.options(7L).contractInForce()).isFalse();
        assertThat(service.options(7L).contractId()).isNull();
        AutoSpottingContract c = contractInForce();
        AllowlistOperatorService.BasisOptions options = service.options(7L);
        assertThat(options.contractInForce()).isTrue();
        assertThat(options.contractId()).isEqualTo(c.getId());
        assertThat(options.accountName()).isEqualTo("prod");
        assertThatThrownBy(() -> service.options(8L))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    @Test
    void list_showsWhetherTheContractIsStillInForce() {
        AutoSpottingContract ended = new AutoSpottingContract();
        ended.setId(UUID.randomUUID());
        ended.setClientId(3L);
        ended.setEffectiveFrom(Instant.now().minus(Duration.ofDays(30)));
        ended.setEffectiveTo(Instant.now().minus(Duration.ofDays(1)));
        AutoSpottingAllowlistEntry row = new AutoSpottingAllowlistEntry();
        row.setCloudAccountId(7L);
        row.setProvider(Provider.AWS);
        row.setBillingBasis("CONTRACT");
        row.setContractId(ended.getId());
        row.setReason("r");
        row.setAddedBy("1");
        row.setAddedAt(Instant.now());
        when(allowlist.findAll()).thenReturn(List.of(row));
        when(accounts.findAllById(any())).thenReturn(List.of(account));
        when(contracts.findById(ended.getId())).thenReturn(Optional.of(ended));
        List<AllowlistView> views = service.list();
        assertThat(views).hasSize(1);
        assertThat(views.get(0).contractInForce()).isFalse();
        assertThat(views.get(0).accountName()).isEqualTo("prod");
    }

    @Test
    void remove_deletesTheRowWithAReason_andAuditsIt() {
        AutoSpottingAllowlistEntry row = new AutoSpottingAllowlistEntry();
        row.setCloudAccountId(7L);
        row.setProvider(Provider.AWS);
        row.setBillingBasis("NO_FEE");
        row.setReason("sandbox");
        when(allowlist.findById(7L)).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.remove(7L, " ", ACTOR))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
        verify(allowlist, never()).delete(any());
        service.remove(7L, "pilot over", ACTOR);
        verify(allowlist).delete(row);
        verify(audit).write(any());
        assertThatThrownBy(() -> service.remove(9L, "x", ACTOR))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }
}
