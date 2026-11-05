package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.service.alert.AlertPreviewService;
import com.xammer.cloud.xampulse.service.alert.AlertRuleService;
import com.xammer.cloud.xampulse.service.alert.ChannelService;
import com.xammer.cloud.xampulse.service.alert.SilenceService;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.platform.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Alert rules, previews, silences (viewer/editor) and notification channels (admin only: they hold destination secrets).
 */
@RestController("xpAlertsController")
@RequestMapping("/api/xamops/xampulse/alerts")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseAlertsController {

    private final AlertRuleService rules;
    private final AlertPreviewService preview;
    private final SilenceService silences;
    private final ChannelService channels;
    private final AuditService audit;
    private final IdempotencyService idempotency;

    public XamPulseAlertsController(AlertRuleService rules, AlertPreviewService preview, SilenceService silences,
                                    ChannelService channels, AuditService audit) {
        this(rules, preview, silences, channels, audit, null);
    }

    @Autowired
    public XamPulseAlertsController(AlertRuleService rules, AlertPreviewService preview, SilenceService silences,
                                    ChannelService channels, AuditService audit, IdempotencyService idempotency) {
        this.idempotency = idempotency;
        this.rules = rules;
        this.preview = preview;
        this.silences = silences;
        this.channels = channels;
        this.audit = audit;
    }

    // ---- rules ----

    @GetMapping("/rules")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpAlertRule> listRules(@AuthenticationPrincipal ClientUserDetails user, @RequestParam List<Long> accountIds) {
        return rules.list(user, accountIds);
    }

    @PostMapping("/rules")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpAlertRule createRule(@AuthenticationPrincipal ClientUserDetails user, @Valid @RequestBody XpAlertRule body, HttpServletRequest http,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return once(user, "POST /alerts/rules", idempotencyKey, body, XpAlertRule.class, () -> {
            XpAlertRule saved = rules.create(user, body);
            audit.record(user, saved.scope().accountIds().get(0), "alerts.rule.create", "rule:" + saved.id(), XamPulseControllerSupport.ip(http));
            return saved;
        });
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpAlertRule updateRule(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long id,
                                  @Valid @RequestBody XpAlertRule body, HttpServletRequest http) {
        XpAlertRule saved = rules.update(user, id, body);
        audit.record(user, saved.scope().accountIds().get(0), "alerts.rule.update", "rule:" + id, XamPulseControllerSupport.ip(http));
        return saved;
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public ResponseEntity<Void> deleteRule(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long id, HttpServletRequest http) {
        rules.delete(user, id);
        audit.record(user, null, "alerts.rule.delete", "rule:" + id, XamPulseControllerSupport.ip(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rules/preview")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpAlertPreview previewRule(@AuthenticationPrincipal ClientUserDetails user, @Valid @RequestBody XpAlertRule body) {
        return preview.preview(user, body);
    }

    // ---- silences ----

    @GetMapping("/silences")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpSilence> listSilences(@AuthenticationPrincipal ClientUserDetails user, @RequestParam List<Long> accountIds) {
        return silences.list(user, accountIds);
    }

    @PostMapping("/silences")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpSilence createSilence(@AuthenticationPrincipal ClientUserDetails user, @Valid @RequestBody XpSilence body, HttpServletRequest http,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return once(user, "POST /alerts/silences", idempotencyKey, body, XpSilence.class, () -> {
            XpSilence saved = silences.create(user, body);
            audit.record(user, saved.accountId(), "alerts.silence.create", "silence:" + saved.id(), XamPulseControllerSupport.ip(http));
            return saved;
        });
    }

    @DeleteMapping("/silences/{id}")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public ResponseEntity<Void> deleteSilence(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long id) {
        silences.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    // ---- channels (admin) ----

    @GetMapping("/channels")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public List<XpChannel> listChannels(@AuthenticationPrincipal ClientUserDetails user, @RequestParam Long accountId) {
        return channels.list(user, accountId);
    }

    @PostMapping("/channels")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public XpChannel createChannel(@AuthenticationPrincipal ClientUserDetails user, @Valid @RequestBody XpChannelInput body, HttpServletRequest http,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return once(user, "POST /alerts/channels", idempotencyKey, body, XpChannel.class, () -> {
            XpChannel saved = channels.create(user, body);
            audit.record(user, saved.accountId(), "alerts.channel.create", "channel:" + saved.id() + ":" + saved.kind(), XamPulseControllerSupport.ip(http));
            return saved;
        });
    }

    @PutMapping("/channels/{id}")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public XpChannel updateChannel(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long id,
                                   @Valid @RequestBody XpChannelInput body, HttpServletRequest http) {
        XpChannel saved = channels.update(user, id, body);
        audit.record(user, saved.accountId(), "alerts.channel.update", "channel:" + id, XamPulseControllerSupport.ip(http));
        return saved;
    }

    @DeleteMapping("/channels/{id}")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public ResponseEntity<Void> deleteChannel(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long id, HttpServletRequest http) {
        channels.delete(user, id);
        audit.record(user, null, "alerts.channel.delete", "channel:" + id, XamPulseControllerSupport.ip(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/channels/{id}/test")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public ResponseEntity<Void> testChannel(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long id) {
        channels.test(user, id);
        return ResponseEntity.noContent().build();
    }

    /** Runs a creating action once per Idempotency-Key (no key, or no service wired: just run it). */
    private <T> T once(ClientUserDetails user, String route, String key, Object body, Class<T> type, java.util.function.Supplier<T> action) {
        return idempotency == null ? action.get() : idempotency.execute(user.getId(), route, key, body, type, action);
    }
}
