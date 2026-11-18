package com.xammer.cloud.service.autospotting.core.plan;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.AutoSpottingAllowlistEntry;
import com.xammer.cloud.domain.autospotting.ledger.AutoSpottingContract;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository;
import com.xammer.cloud.repository.autospotting.ledger.AutoSpottingContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code FeeBasisResolver} reads the contract and the allowlist basis (design-core.md §7.3, plan-phase-15.md
 * task 15.4): a contract in force gives {@code {contractId, sharePct, monthlyCap}}; a NO_FEE allowlist
 * row wins over a contract; otherwise {@code NO_CONTRACT}. An unreadable store never implies a fee.
 */
class FeeBasisResolverTest {

    private AutoSpottingAllowlistEntryRepository allowlist;
    private AutoSpottingContractRepository contracts;
    private CloudAccountRepository accounts;
    private FeeBasisResolver resolver;

    @BeforeEach
    void setUp() {
        allowlist = mock(AutoSpottingAllowlistEntryRepository.class);
        contracts = mock(AutoSpottingContractRepository.class);
        accounts = mock(CloudAccountRepository.class);
        Client client = new Client();
        client.setId(3L);
        CloudAccount account = new CloudAccount();
        account.setId(7L);
        account.setClient(client);
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(allowlist.findById(7L)).thenReturn(Optional.empty());
        when(contracts.findInForce(anyLong(), any())).thenReturn(List.of());
        resolver = new FeeBasisResolver(allowlist, contracts, accounts);
    }

    private AutoSpottingContract contract(String share, String cap) {
        AutoSpottingContract c = new AutoSpottingContract();
        c.setId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        c.setClientId(3L);
        c.setSharePct(new BigDecimal(share));
        c.setMonthlyCap(cap == null ? null : new BigDecimal(cap));
        c.setEffectiveFrom(Instant.now().minus(Duration.ofDays(2)));
        when(contracts.findInForce(anyLong(), any())).thenReturn(List.of(c));
        return c;
    }

    private static AutoSpottingAllowlistEntry row(String basis) {
        AutoSpottingAllowlistEntry e = new AutoSpottingAllowlistEntry();
        e.setCloudAccountId(7L);
        e.setBillingBasis(basis);
        return e;
    }

    @Test
    void noContract_isNoContract() {
        assertThat(resolver.resolve(7L, null)).isEqualTo("{\"contractId\":null,\"reason\":\"NO_CONTRACT\"}");
    }

    @Test
    void contractInForce_givesTheContractTerms_exactly() {
        contract("12.500000", "1000.00");
        assertThat(resolver.resolve(7L, null)).isEqualTo("{\"contractId\":\"11111111-2222-3333-4444-555555555555\","
                + "\"sharePct\":12.500000,\"monthlyCap\":1000.00}");
    }

    @Test
    void noCap_isNull() {
        contract("20.000000", null);
        assertThat(resolver.resolve(7L, null)).endsWith("\"sharePct\":20.000000,\"monthlyCap\":null}");
    }

    @Test
    void aNoFeeAllowlistRow_winsOverAContract() {
        contract("20", "500.00");
        when(allowlist.findById(7L)).thenReturn(Optional.of(row("NO_FEE")));
        assertThat(resolver.resolve(7L, null)).isEqualTo("{\"contractId\":null,\"reason\":\"NO_FEE_ACCOUNT\"}");
    }

    @Test
    void aContractAllowlistRow_followsTheContractInForceNow() {
        when(allowlist.findById(7L)).thenReturn(Optional.of(row("CONTRACT")));
        // Its contract has ended and none is in force: no fee applies.
        assertThat(resolver.resolve(7L, null)).isEqualTo(FeeBasisResolver.NO_CONTRACT);
        contract("20", null);
        assertThat(resolver.resolve(7L, null)).contains("\"contractId\":\"11111111");
    }

    @Test
    void unknownAccountOrUnreadableStore_neverImpliesAFee() {
        assertThat(resolver.resolve(99L, null)).isEqualTo(FeeBasisResolver.NO_CONTRACT);
        when(contracts.findInForce(anyLong(), any())).thenThrow(new IllegalStateException("table missing"));
        assertThat(resolver.resolve(7L, null)).isEqualTo(FeeBasisResolver.NO_CONTRACT);
    }
}
