package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpArchiveEstimate;
import com.xammer.cloud.xampulse.dto.XpArchiveSearchJob;
import com.xammer.cloud.xampulse.dto.XpScope;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.query.FilterAst;
import com.xammer.cloud.xampulse.service.query.FilterDslParser;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts and runs archive searches on a small bounded pool (2 threads, queue of 10; overflow is a 429). The server
 * recomputes the estimate and refuses if it grew past what the user confirmed, at most one job runs per account, and
 * the file list is taken from the tenant's own manifests only. Failures are stored as stable codes; the exception
 * class goes to the log, never its message.
 */
@Service("xpArchiveSearchRunner")
@ConditionalOnProperty(name = {"xampulse.enabled", "xampulse.archive.enabled"}, havingValue = "true")
public class ArchiveSearchRunner {

    private static final Logger log = LoggerFactory.getLogger(ArchiveSearchRunner.class);
    static final int HARD_ROW_LIMIT = 1000;

    private final ArchiveSearchService search;
    private final ArchiveSearchExecutor executor;
    private final ArchiveSearchJobStore jobs;
    private final XamPulseProperties props;
    private final AuditService audit;
    private final Executor pool;
    private final ThreadPoolExecutor ownedPool;
    private final Clock clock;

    @Autowired
    public ArchiveSearchRunner(ArchiveSearchService search, ArchiveSearchExecutor executor, ArchiveSearchJobStore jobs,
                               XamPulseProperties props, AuditService audit) {
        this.search = search;
        this.executor = executor;
        this.jobs = jobs;
        this.props = props;
        this.audit = audit;
        this.ownedPool = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10), r -> {
            Thread t = new Thread(r, "xp-archive-search");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.pool = ownedPool;
        this.clock = Clock.systemUTC();
    }

    /** For tests: supply the pool and the clock. */
    ArchiveSearchRunner(ArchiveSearchService search, ArchiveSearchExecutor executor, ArchiveSearchJobStore jobs,
                        XamPulseProperties props, AuditService audit, Executor pool, Clock clock) {
        this.search = search;
        this.executor = executor;
        this.jobs = jobs;
        this.props = props;
        this.audit = audit;
        this.pool = pool;
        this.ownedPool = null;
        this.clock = clock;
    }

    /**
     * @return the QUEUED job (already DONE for an empty range)
     * @throws XamPulseException 409 when the estimate grew or another search is running; 429 when the queue is full
     */
    public XpArchiveSearchJob start(long ownerUserId, Long actorUserId, XpAccountConfig cfg, String awsAccountId,
                                    XpScope scope, String query, long confirmedBytes) {
        long accountId = scope.accountIds().get(0);
        FilterAst filter = FilterDslParser.parse(query == null ? "" : query);
        Instant now = clock.instant();
        XpArchiveEstimate estimate = search.estimate(cfg, awsAccountId, scope.from(), scope.to(), now);
        if (estimate.bytes() > confirmedBytes) {
            throw XamPulseException.estimateChanged();
        }
        List<ArchiveManifest.Entry> files = search.files(cfg, awsAccountId, scope.from(), scope.to());
        if (files.size() > props.getArchive().getMaxSearchFiles()) {
            throw XamPulseException.invalid("That range covers too many archive files. Narrow the time range.");
        }
        String prefix = ArchiveKeyLayout.ROOT + "logs/account=" + awsAccountId + "/";
        files = files.stream().filter(f -> f.key() != null && f.key().startsWith(prefix)).toList();

        String id = jobs.newId();
        if (!jobs.tryLock(accountId, id)) {
            throw XamPulseException.conflict("An archive search is already running for this account.");
        }
        XpArchiveSearchJob queued = new XpArchiveSearchJob(id, "QUEUED", estimate, 0, files.size(), null, null, null, now, null);
        try {
            jobs.save(new ArchiveSearchJobStore.Record(ownerUserId, accountId, queued));
            audit.recordForUser(ownerUserId, accountId, "archive.search.run",
                    AuditService.describeQuery(query) + ",bytes=" + estimate.bytes() + ",files=" + files.size()
                            + (actorUserId == null ? "" : ",actor=" + actorUserId), null);
            List<ArchiveManifest.Entry> toRead = files;
            QueryScope tenantScope = QueryScope.of(List.of(accountId), scope.from(), scope.to());
            pool.execute(() -> runJob(id, accountId, cfg, awsAccountId, tenantScope, toRead, filter));
        } catch (RejectedExecutionException e) {
            jobs.delete(id);
            jobs.unlock(accountId);
            throw XamPulseException.rateLimited();
        } catch (RuntimeException e) {
            jobs.unlock(accountId);
            throw e;
        }
        return queued;
    }

    /** Marks a QUEUED or RUNNING job CANCELLED; the runner stops between files. */
    public XpArchiveSearchJob cancel(ArchiveSearchJobStore.Record rec) {
        XpArchiveSearchJob j = rec.job();
        if (!"QUEUED".equals(j.state()) && !"RUNNING".equals(j.state())) {
            return j;
        }
        XpArchiveSearchJob cancelled = new XpArchiveSearchJob(j.id(), "CANCELLED", j.estimate(), j.filesDone(), j.filesTotal(),
                null, null, null, j.createdAt(), clock.instant());
        jobs.save(new ArchiveSearchJobStore.Record(rec.ownerUserId(), rec.accountId(), cancelled));
        return cancelled;
    }

    void runJob(String id, long accountId, XpAccountConfig cfg, String awsAccountId, QueryScope scope,
                List<ArchiveManifest.Entry> files, FilterAst filter) {
        AtomicInteger done = new AtomicInteger();
        try {
            if (jobs.isCancelled(id)) {
                return;
            }
            jobs.update(id, j -> withState(j, "RUNNING", done.get()));
            Instant deadline = clock.instant().plusSeconds(Math.max(10, props.getArchive().getSearchTimeoutSec()));
            int rowLimit = Math.max(1, Math.min(props.getArchive().getMaxSearchRows(), HARD_ROW_LIMIT));
            ArchiveSearchExecutor.Result result = executor.search(cfg, awsAccountId, scope, files, filter, rowLimit, n -> {
                done.set(n);
                jobs.update(id, j -> withState(j, "RUNNING", n));
            }, () -> jobs.isCancelled(id) || clock.instant().isAfter(deadline));
            if (jobs.isCancelled(id)) {
                return;
            }
            if (done.get() < files.size()) {
                finish(id, "FAILED", done.get(), null, false, ArchiveSearchExecutor.Failure.TIMEOUT);
                return;
            }
            finish(id, "DONE", done.get(), result.rows(), result.truncated(), null);
        } catch (ArchiveSearchExecutor.Failure f) {
            log.warn("XamPulse archive search failed ({})", f.code());
            finish(id, "FAILED", done.get(), null, false, f.code());
        } catch (RuntimeException e) {
            log.warn("XamPulse archive search failed: {}", e.getClass().getSimpleName());
            finish(id, "FAILED", done.get(), null, false, ArchiveSearchExecutor.Failure.INTERNAL);
        } finally {
            jobs.unlock(accountId);
        }
    }

    private void finish(String id, String state, int filesDone, List<com.xammer.cloud.xampulse.dto.XpLogRow> rows,
                        boolean truncated, String errorCode) {
        Instant at = clock.instant();
        jobs.update(id, j -> new XpArchiveSearchJob(j.id(), state, j.estimate(), filesDone, j.filesTotal(),
                rows, "DONE".equals(state) ? truncated : null, errorCode, j.createdAt(), at));
    }

    private static XpArchiveSearchJob withState(XpArchiveSearchJob j, String state, int filesDone) {
        return new XpArchiveSearchJob(j.id(), state, j.estimate(), filesDone, j.filesTotal(), null, null, null, j.createdAt(), null);
    }

    @PreDestroy
    void shutdown() {
        if (ownedPool != null) {
            ownedPool.shutdownNow();
        }
    }
}
