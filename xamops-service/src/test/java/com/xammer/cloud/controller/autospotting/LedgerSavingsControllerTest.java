package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.ledger.money.Money;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService.GroupMonth;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService.ReconciliationStatus;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService.SavingsSummary;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** plan-phase-17.md 17.4: the shell's ledger reads go through the read service, guard first, money as strings. */
class LedgerSavingsControllerTest {
    private final CloudAccountAccessService access = mock(CloudAccountAccessService.class);
    private final SavingsLedgerReadService ledger = mock(SavingsLedgerReadService.class);
    private final SpotGroupRepository groups = mock(SpotGroupRepository.class);
    private final LedgerSavingsController controller = new LedgerSavingsController(access, ledger, groups,
            Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC));
    private final ClientUserDetails user = new ClientUserDetails("owner", "x",
            List.of(new SimpleGrantedAuthority("ROLE_USER")), 7L, 100L, "t");
    private final CloudAccount account = new CloudAccount();

    LedgerSavingsControllerTest() {
        account.setId(7L);
        account.setProvider("AWS");
    }

    private static Money usd(String v) { return Money.of(v, "USD"); }

    @Test
    void summaryIsTheOpenMonthsAccrual_asAString_withItsLabel() {
        when(access.requireAccess(user, 7L)).thenReturn(account);
        var z = usd("0");
        when(ledger.getSavingsSummary(eq(account), eq(Instant.parse("2026-11-01T00:00:00Z")),
                eq(Instant.parse("2026-12-01T00:00:00Z")))).thenReturn(new SavingsSummary(7L, "AWS",
                Instant.EPOCH, Instant.EPOCH, z, z, z, z, z, usd("9.725"), ReconciliationStatus.PENDING, false, List.of()));
        var body = controller.summary(7L, user).getBody();
        assertThat(body.accruingNotFinal().amount()).isEqualTo("9.73");
        assertThat(body.accruingNotFinal().currency()).isEqualTo("USD");
        assertThat(body.label()).isEqualTo("Accruing (not final)");
        assertThat(body.month()).isEqualTo("2026-11");
    }

    @Test
    void groupMonthsAreLabelledAccruingOrNotInvoiced_neverBillable() {
        when(access.requireAccess(user, 7L)).thenReturn(account);
        UUID gid = UUID.randomUUID();
        SpotGroup g = new SpotGroup();
        g.setId(gid);
        g.setCloudAccountId(7L);
        when(groups.findById(gid)).thenReturn(Optional.of(g));
        when(ledger.getGroupMonths(eq(account), eq(gid), eq(YearMonth.of(2026, 6)), eq(YearMonth.of(2026, 11))))
                .thenReturn(List.of(new GroupMonth(YearMonth.of(2026, 10), "CLOSED", false, usd("291.60")),
                        new GroupMonth(YearMonth.of(2026, 11), "OPEN", false, usd("9.72"))));
        var body = controller.groupSavings(7L, gid, user).getBody();
        assertThat(body).extracting(LedgerSavingsController.GroupMonthDto::label)
                .containsExactly("Not invoiced", "Accruing (not final)");
        assertThat(body.get(0).amount().amount()).isEqualTo("291.60");
        assertThat(body.toString()).doesNotContainIgnoringCase("billable").doesNotContainIgnoringCase("realized");
    }

    @Test
    void anotherAccountsGroupIs404_andNothingIsRead() {
        when(access.requireAccess(user, 7L)).thenReturn(account);
        UUID gid = UUID.randomUUID();
        SpotGroup g = new SpotGroup();
        g.setId(gid);
        g.setCloudAccountId(8L);
        when(groups.findById(gid)).thenReturn(Optional.of(g));
        assertThatThrownBy(() -> controller.groupSavings(7L, gid, user)).isInstanceOf(AutoSpottingApiException.class);
        verify(ledger, never()).getGroupMonths(any(), any(), any(), any());
    }

    @Test
    void theGuardRunsFirst() {
        when(access.requireAccess(user, 7L)).thenThrow(new AccessDeniedException("no"));
        assertThatThrownBy(() -> controller.summary(7L, user)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(ledger);
    }
}
