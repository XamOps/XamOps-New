package com.xammer.cloud.service.autospotting.ledger.contract;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerGoLive;
import com.xammer.cloud.repository.autospotting.ledger.SavingsLedgerGoLiveRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The go-live instant per provider (design-ledger.md §2.3, §13.3, FR-LED-21): set once, never
 * changed. No billable entry covers usage before it and nothing is back-filled, so an instant in the
 * past is refused. It is a record only; billing stays off.
 */
@Service
public class GoLiveService {

    private final SavingsLedgerGoLiveRepository goLives;
    private final AutoSpottingAuditWriter auditWriter;

    public GoLiveService(SavingsLedgerGoLiveRepository goLives, AutoSpottingAuditWriter auditWriter) {
        this.goLives = goLives;
        this.auditWriter = auditWriter;
    }

    @Transactional(readOnly = true)
    public List<SavingsLedgerGoLive> list() {
        return goLives.findAll();
    }

    /** Parses the path segment ({@code aws} or {@code gcp}, any case) or answers 404. */
    public static Provider parseProvider(String raw) {
        try {
            return Provider.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown provider.");
        }
    }

    @Transactional
    public SavingsLedgerGoLive set(Provider provider, Instant goLiveAt, Actor actor, String actorLabel,
                                   String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED",
                    "reason is required, 1 to 500 characters.");
        }
        Instant now = Instant.now();
        if (goLiveAt != null && goLiveAt.isBefore(now)) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED",
                    "goLiveAt cannot be in the past; nothing is back-filled.");
        }
        if (goLives.existsById(provider.name())) {
            throw goLiveAlreadySet();
        }
        SavingsLedgerGoLive row = new SavingsLedgerGoLive();
        row.setProvider(provider.name());
        row.setGoLiveAt(goLiveAt == null ? now : goLiveAt);
        row.setSetBy(actorLabel);
        row.setSetAt(now);
        SavingsLedgerGoLive saved;
        try {
            saved = goLives.save(row);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two admins racing: the primary key lets one win.
            throw goLiveAlreadySet();
        }
        auditWriter.write(AuditRecord.builder("BILLOPS_ADMIN", actor, "GOLIVE_SET", "SUCCESS")
                .provider(provider.name())
                .afterState("{\"provider\":\"" + provider.name() + "\",\"goLiveAt\":\"" + saved.getGoLiveAt()
                        + "\",\"reason\":\"" + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}")
                .build());
        return saved;
    }

    private static AutoSpottingApiException goLiveAlreadySet() {
        return new AutoSpottingApiException(HttpStatus.CONFLICT, "GOLIVE_ALREADY_SET",
                "The go-live instant for this provider is already set and cannot change.");
    }
}
