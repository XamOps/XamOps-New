package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpArchiveSearchJob;
import com.xammer.cloud.xampulse.dto.XpArchiveSearchRunRequest;
import com.xammer.cloud.xampulse.dto.XpScope;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.archive.ArchiveSearchJobStore;
import com.xammer.cloud.xampulse.service.archive.ArchiveSearchRunner;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.platform.XpIdempotencyService;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Runs and polls archive searches. These routes exist only when {@code xampulse.archive.enabled=true} (default OFF), so
 * with the default flags they are 404. A job is visible to the user who started it only.
 */
@RestController("xpArchiveSearchRunController")
@RequestMapping("/api/xamops/xampulse/archive-searches")
@ConditionalOnProperty(name = {"xampulse.enabled", "xampulse.archive.enabled"}, havingValue = "true")
public class XamPulseArchiveSearchRunController {

    private final XamPulseAccessGuard guard;
    private final XpAccountConfigRepository configs;
    private final ArchiveSearchRunner runner;
    private final ArchiveSearchJobStore jobs;
    private final XpIdempotencyService idempotency;
    private final AuditService audit;

    public XamPulseArchiveSearchRunController(XamPulseAccessGuard guard, XpAccountConfigRepository configs, ArchiveSearchRunner runner,
                                              ArchiveSearchJobStore jobs, XpIdempotencyService idempotency, AuditService audit) {
        this.guard = guard;
        this.configs = configs;
        this.runner = runner;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @PostMapping("/run")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public ResponseEntity<XpArchiveSearchJob> run(@AuthenticationPrincipal ClientUserDetails user,
                                                  @Valid @RequestBody XpArchiveSearchRunRequest request,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                  HttpServletRequest http) {
        XpScope scope = request.scope();
        if (scope.accountIds() == null || scope.accountIds().size() != 1) {
            throw XamPulseException.invalid("An archive search targets exactly one account.");
        }
        Long actor = XamPulseControllerSupport.impersonationActor(http);
        XpArchiveSearchJob job = idempotency.execute(user.getId(), "POST /archive-searches/run", idempotencyKey, request,
                XpArchiveSearchJob.class, () -> {
                    CloudAccount account = guard.require(user, scope.accountIds().get(0));
                    if (account.getAwsAccountId() == null || (account.getProvider() != null && !"AWS".equalsIgnoreCase(account.getProvider()))) {
                        throw XamPulseException.notFound();
                    }
                    XpAccountConfig cfg = configs.findByCloudAccountId(account.getId()).orElseThrow(XamPulseException::notSetUp);
                    return runner.start(user.getId(), actor, cfg, account.getAwsAccountId(), scope, request.query(), request.confirmedBytes());
                });
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @GetMapping("/{id}")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpArchiveSearchJob get(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String id) {
        ArchiveSearchJobStore.Record rec = owned(user, id);
        XpArchiveSearchJob job = rec.job();
        if ("DONE".equals(job.state())) {
            audit.record(user, rec.accountId(), "archive.search.results", "job:" + job.id() + ",rows=" + (job.rows() == null ? 0 : job.rows().size()), null);
            return job;
        }
        return new XpArchiveSearchJob(job.id(), job.state(), job.estimate(), job.filesDone(), job.filesTotal(), null, null,
                job.errorCode(), job.createdAt(), job.finishedAt());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpArchiveSearchJob cancel(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String id) {
        return runner.cancel(owned(user, id));
    }

    /**
     * The job, only for the user who started it and only while they can still access the account. XamOps has a
     * single product role ({@code ROLE_XAMOPS}), so there is no admin override: polling and cancelling are both
     * owner-only, and anyone else gets the same 404 as for an unknown id.
     */
    private ArchiveSearchJobStore.Record owned(ClientUserDetails user, String id) {
        ArchiveSearchJobStore.Record rec = jobs.find(id).orElseThrow(XamPulseException::notFound);
        boolean owner = user.getId() != null && user.getId() == rec.ownerUserId();
        if (!owner) {
            throw XamPulseException.notFound();
        }
        guard.require(user, rec.accountId()); // the grant may have been removed since the job started
        return rec;
    }
}
