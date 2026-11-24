package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.ledger.money.Money;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The shell's ledger reads (plan-phase-17.md 17.4, design-ledger.md §10.2): both go through
 * {@link SavingsLedgerReadService}, the one reader of ledger savings, and take the guard-resolved account.
 * <ul>
 *   <li>{@code GET /api/autospotting/v2/accounts/{accountId}/savings/summary}: the open month's
 *       {@code accruingNotFinal} for the "Accruing MTD (not final)" KPI.</li>
 *   <li>{@code GET /api/autospotting/v2/accounts/{accountId}/groups/{groupId}/savings}: a group's measured net
 *       per month over the last 6 months, for the Savings tab, each with its label.</li>
 * </ul>
 * Money is a decimal string with its currency, never a JSON number. Nothing here is billable: these are
 * shadow figures while billing is off, and the label says which kind each one is.
 */
@RestController
@RequestMapping("/api/autospotting/v2")
public class LedgerSavingsController {

    static final int GROUP_MONTHS = 6;

    /** A money amount as the API sends it. */
    public record MoneyDto(String amount, String currency) {
        static MoneyDto of(Money m) {
            return new MoneyDto(m.toMinorUnit().amount().toPlainString(), m.currency().getCurrencyCode());
        }
    }

    public record SummaryDto(String month, MoneyDto accruingNotFinal, String label) {
    }

    /** {@code label} is "Accruing (not final)" for an open month and "Not invoiced" for a closed shadow month. */
    public record GroupMonthDto(String month, String status, MoneyDto amount, String label) {
    }

    private final CloudAccountAccessService access;
    private final SavingsLedgerReadService ledger;
    private final SpotGroupRepository groups;
    private final Clock clock;

    @Autowired
    public LedgerSavingsController(CloudAccountAccessService access, SavingsLedgerReadService ledger,
                                   SpotGroupRepository groups) {
        this(access, ledger, groups, Clock.systemUTC());
    }

    LedgerSavingsController(CloudAccountAccessService access, SavingsLedgerReadService ledger,
                            SpotGroupRepository groups, Clock clock) {
        this.access = access;
        this.ledger = ledger;
        this.groups = groups;
        this.clock = clock;
    }

    @GetMapping("/accounts/{accountId}/savings/summary")
    public ResponseEntity<SummaryDto> summary(@PathVariable Long accountId,
                                              @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = access.requireAccess(user, accountId);
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var s = ledger.getSavingsSummary(account, start, end);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SummaryDto(month.toString(), MoneyDto.of(s.accruingNotFinal()), "Accruing (not final)"));
    }

    @GetMapping("/accounts/{accountId}/groups/{groupId}/savings")
    public ResponseEntity<List<GroupMonthDto>> groupSavings(@PathVariable Long accountId, @PathVariable UUID groupId,
                                                            @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = access.requireAccess(user, accountId);
        SpotGroup g = groups.findById(groupId).orElse(null);
        if (g == null || !account.getId().equals(g.getCloudAccountId())) {
            throw new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Group not found.");
        }
        YearMonth to = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        List<GroupMonthDto> body = ledger.getGroupMonths(account, groupId, to.minusMonths(GROUP_MONTHS - 1L), to)
                .stream()
                .map(m -> new GroupMonthDto(m.month().toString(), m.status(), MoneyDto.of(m.net()), label(m)))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    static String label(SavingsLedgerReadService.GroupMonth m) {
        if ("OPEN".equals(m.status())) {
            return "Accruing (not final)";
        }
        // A closed month here is the group's measured net, never the invoice: it is labelled as not invoiced
        // even when the statement is invoiceable, because the invoiced figure is the statement's, not this one.
        return "Not invoiced";
    }
}
