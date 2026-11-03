package com.xammer.cloud.service.autospotting.core.switches;

import com.xammer.cloud.domain.autospotting.AutoSpottingSwitch;
import com.xammer.cloud.repository.autospotting.AutoSpottingSwitchRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The operator half of the kill-switch hierarchy (design-core.md §6.6, plan-phase-09.md task 9.4):
 * the {@code GET /switches} read and the {@code PUT /switches/{scopeKey}} write that
 * {@link com.xammer.cloud.controller.autospotting.AutoSpottingOperatorController} serves.
 *
 * <p>The read returns, per scope key: the deployed property value, the DB override ({@code engaged}),
 * the effective value ({@code property AND NOT db.engaged}, §6.1), and {@code changed_by/at} and
 * {@code reason}. The write engages or releases a scope under a {@code version} guard; two racing
 * operators can't both win (409 {@code SWITCH_VERSION_MISMATCH}). A tighten-only scope (one backed by
 * a deploy property) rejects a release that would exceed the property (409 {@code SWITCH_PROPERTY_OFF}):
 * releasing the DB override on a scope whose property is already off would try to turn the feature
 * on past the property, which the DB may never do. Each write also records an {@code OPERATOR} audit
 * row with the before and after in the same transaction.
 *
 * <p>{@link SwitchService} keeps owning the cached read on the request path; this service owns the
 * operator read and write. The property half is read the same way the gate reads it
 * ({@link AutoSpottingSwitchProperties}, {@link AutoSpottingFeatureFlags}, and row (a)
 * {@code autospotting.enabled}).
 */
@Service
public class SwitchOperatorService {

    private static final Logger log = LoggerFactory.getLogger(SwitchOperatorService.class);

    private final AutoSpottingSwitchRepository switches;
    private final AutoSpottingSwitchProperties properties;
    private final AutoSpottingFeatureFlags featureFlags;
    private final AutoSpottingAuditWriter auditWriter;
    private final boolean globalEnabledProperty;

    public SwitchOperatorService(AutoSpottingSwitchRepository switches,
                                 AutoSpottingSwitchProperties properties,
                                 AutoSpottingFeatureFlags featureFlags,
                                 AutoSpottingAuditWriter auditWriter,
                                 @Value("${autospotting.enabled:false}") boolean globalEnabledProperty) {
        this.switches = switches;
        this.properties = properties;
        this.featureFlags = featureFlags;
        this.auditWriter = auditWriter;
        this.globalEnabledProperty = globalEnabledProperty;
    }

    /** One scope's full view for {@code GET /switches} (design-core.md §6.6). */
    public record SwitchView(
            String scopeKey,
            Boolean propertyValue,
            boolean engaged,
            Boolean effectiveValue,
            String reason,
            String changedBy,
            Instant changedAt,
            long version) {
    }

    /** Every switch row with its property, DB override and effective value (design-core.md §6.6). */
    public List<SwitchView> list() {
        List<SwitchView> views = new ArrayList<>();
        for (AutoSpottingSwitch row : switches.findAllByOrderByChangedAtDesc()) {
            views.add(toView(row));
        }
        return views;
    }

    private SwitchView toView(AutoSpottingSwitch row) {
        Boolean property = propertyValueFor(row.getScopeKey());
        // Effective = property AND NOT db.engaged (§6.1). Unknown property (account/freeze scopes)
        // leaves effectiveValue null: those scopes have only a DB half.
        Boolean effective = property == null ? null : (property && !row.isEngaged());
        return new SwitchView(row.getScopeKey(), property, row.isEngaged(), effective,
                row.getReason(), row.getChangedBy(), row.getChangedAt(), row.getVersion());
    }

    /**
     * Engage or release a scope under a {@code version} guard (design-core.md §6.6), writing an
     * {@code OPERATOR} audit row in the same transaction. Returns the new view.
     *
     * @throws AutoSpottingApiException 409 {@code SWITCH_PROPERTY_OFF} (release would exceed the
     *         property), 409 {@code SWITCH_VERSION_MISMATCH} (the row changed under the operator), or
     *         404 when the scope does not exist
     */
    @Transactional
    public SwitchView setEngaged(String scopeKey, boolean engaged, long expectedVersion, Actor actor,
                                 String reason) {
        AutoSpottingSwitch existing = switches.findById(scopeKey)
                .orElseThrow(() -> new AutoSpottingApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Switch not found."));

        // Tighten-only rule (§6.1, §6.6): a release (engaged=false) on a scope whose property is off
        // would try to turn the feature on past the property. Reject it.
        Boolean property = propertyValueFor(scopeKey);
        if (!engaged && property != null && !property) {
            throw AutoSpottingApiException.switchPropertyOff();
        }

        boolean before = existing.isEngaged();
        int updated = switches.updateGuarded(scopeKey, engaged, reason, actorLabel(actor), Instant.now(),
                expectedVersion);
        if (updated == 0) {
            throw AutoSpottingApiException.switchVersionMismatch();
        }

        auditWriter.write(AuditRecord.builder("OPERATOR_SWITCH", actor, "SWITCH:" + scopeKey, "SUCCESS")
                .beforeState("{\"engaged\":" + before + "}")
                .afterState("{\"engaged\":" + engaged + ",\"reason\":\"" + escape(reason) + "\"}")
                .build());

        AutoSpottingSwitch after = switches.findById(scopeKey).orElseThrow();
        log.info("operator switch {} engaged {} -> {} (reason length {})", scopeKey, before, engaged,
                reason == null ? 0 : reason.length());
        return toView(after);
    }

    /**
     * The deployed property value behind a scope key, or null for a DB-only scope (per-account,
     * freeze). Read the same way {@link ActionGate} reads the property half (§6.1).
     */
    Boolean propertyValueFor(String scopeKey) {
        if (scopeKey == null) {
            return null;
        }
        if (SwitchState.GLOBAL.equals(scopeKey)) {
            return globalEnabledProperty;
        }
        if (scopeKey.equals(SwitchState.cloudKey(true))) {
            return properties.cloudEnabled(true);
        }
        if (scopeKey.equals(SwitchState.cloudKey(false))) {
            return properties.cloudEnabled(false);
        }
        if (scopeKey.equals(SwitchState.conversionsKey(true))) {
            return featureFlags.isAwsConversionsEnabled();
        }
        if (scopeKey.equals(SwitchState.conversionsKey(false))) {
            return featureFlags.isGcpConversionsEnabled();
        }
        if ("LEDGER:aws".equals(scopeKey)) {
            return properties.ledgerWriterEnabled(true);
        }
        if ("LEDGER:gcp".equals(scopeKey)) {
            return properties.ledgerWriterEnabled(false);
        }
        if ("LEDGER:billing".equals(scopeKey)) {
            return properties.ledgerBillingEnabled();
        }
        // ACCOUNT:*, FREEZE and anything else: DB-only, no property.
        return null;
    }

    private static String actorLabel(Actor actor) {
        if (actor == null) {
            return "operator";
        }
        if (actor.userId() != null) {
            return String.valueOf(actor.userId());
        }
        return actor.type() == null ? "operator" : actor.type().name().toLowerCase();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
