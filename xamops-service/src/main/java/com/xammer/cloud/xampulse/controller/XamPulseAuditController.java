package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpAuditEntry;
import com.xammer.cloud.xampulse.dto.XpPage;
import com.xammer.cloud.xampulse.repository.XpAuditRepository;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("xpAuditController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseAuditController {

    private final XpAuditRepository audit;
    private final XamPulseAccessGuard guard;

    public XamPulseAuditController(XpAuditRepository audit, XamPulseAccessGuard guard) {
        this.audit = audit;
        this.guard = guard;
    }

    @GetMapping("/audit")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public XpPage<XpAuditEntry> audit(@AuthenticationPrincipal ClientUserDetails user, @RequestParam Long accountId,
                                      @RequestParam(defaultValue = "0") int page) {
        guard.require(user, accountId);
        Page<com.xammer.cloud.xampulse.domain.XpAudit> res = audit.findByCloudAccountIdOrderByTsDesc(accountId, PageRequest.of(Math.max(0, page), 50));
        return new XpPage<>(res.getContent().stream()
                .map(a -> new XpAuditEntry(a.getTs(), a.getUserId(), a.getCloudAccountId(), a.getAction(), a.getTarget(), a.getIp())).toList(),
                res.getNumber(), res.getSize(), res.getTotalElements());
    }
}
