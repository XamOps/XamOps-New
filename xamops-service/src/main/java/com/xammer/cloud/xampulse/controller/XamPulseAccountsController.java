package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpAccountRef;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** AWS accounts the caller can use, with their XamPulse state. Numeric ids, so the UI never guesses them. */
@RestController("xpAccountsController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseAccountsController {

    private final XamPulseAccessGuard guard;
    private final XpAccountConfigRepository configs;

    public XamPulseAccountsController(XamPulseAccessGuard guard, XpAccountConfigRepository configs) {
        this.guard = guard;
        this.configs = configs;
    }

    @GetMapping("/accounts")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpAccountRef> accounts(@AuthenticationPrincipal ClientUserDetails user) {
        List<CloudAccount> accounts = guard.accessibleAwsAccounts(user);
        Map<Long, XpAccountConfig> byId = configs.findByCloudAccountIdIn(accounts.stream().map(CloudAccount::getId).toList())
                .stream().collect(Collectors.toMap(XpAccountConfig::getCloudAccountId, Function.identity()));
        return accounts.stream().map(a -> {
            XpAccountConfig c = byId.get(a.getId());
            XpAccountState state = c == null ? XpAccountState.NOT_SET_UP : parse(c.getState());
            return new XpAccountRef(a.getId(), a.getAwsAccountId(), a.getAccountName() == null ? a.getAwsAccountId() : a.getAccountName(),
                    state, false);
        }).toList();
    }

    private static XpAccountState parse(String s) {
        try {
            return XpAccountState.valueOf(s);
        } catch (RuntimeException e) {
            return XpAccountState.NOT_SET_UP;
        }
    }
}
