package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.setup.BucketHealthService;
import com.xammer.cloud.xampulse.service.setup.DisconnectService;
import com.xammer.cloud.xampulse.service.setup.InstanceCandidateService;
import com.xammer.cloud.xampulse.service.setup.LogSourceService;
import com.xammer.cloud.xampulse.service.setup.SetupChecklistService;
import com.xammer.cloud.xampulse.service.setup.XamPulseSetupService;
import com.xammer.cloud.xampulse.service.setup.XamPulseStackWatcher;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Onboarding endpoints (contract 2.3, setup rows). Every method authorises the account first; admins only where the contract says so. */
@RestController("xpSetupController")
@RequestMapping("/api/xamops/xampulse/accounts/{accountId}")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseSetupController {

    private static final Logger log = LoggerFactory.getLogger(XamPulseSetupController.class);
    static final long STREAM_CAP_MS = 15 * 60 * 1000L;
    static final int MAX_STREAMS = 50;

    private final XamPulseAccessGuard guard;
    private final XamPulseSetupService setup;
    private final InstanceCandidateService instances;
    private final BucketHealthService buckets;
    private final XamPulseStackWatcher watcher;
    private final SetupChecklistService checklist;
    private final DisconnectService disconnect;
    private final LogSourceService sources;
    private final ScheduledExecutorService ticker = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "xp-checklist-stream");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger streams = new AtomicInteger();

    public XamPulseSetupController(XamPulseAccessGuard guard, XamPulseSetupService setup, InstanceCandidateService instances,
                                   BucketHealthService buckets, XamPulseStackWatcher watcher, SetupChecklistService checklist,
                                   DisconnectService disconnect, LogSourceService sources) {
        this.guard = guard;
        this.setup = setup;
        this.instances = instances;
        this.buckets = buckets;
        this.watcher = watcher;
        this.checklist = checklist;
        this.disconnect = disconnect;
        this.sources = sources;
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    /** Authorises the account and makes sure it is an AWS account; both failures look like "not found". */
    private CloudAccount account(ClientUserDetails user, Long accountId) {
        CloudAccount a = guard.require(user, accountId);
        if (a.getAwsAccountId() == null || (a.getProvider() != null && !"AWS".equalsIgnoreCase(a.getProvider()))) {
            throw XamPulseException.notFound();
        }
        return a;
    }

    @GetMapping("/setup")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpSetupOverview overview(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId) {
        return setup.overview(account(user, accountId));
    }

    @GetMapping("/instances")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpPage<XpInstanceCandidate> instances(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId,
                                                 @RequestParam(required = false) String q, @RequestParam(required = false) String state,
                                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        List<XpInstanceCandidate> all = instances.candidates(account(user, accountId));
        String needle = q == null ? "" : q.toLowerCase(Locale.ROOT).trim();
        List<XpInstanceCandidate> filtered = all.stream()
                .filter(c -> state == null || state.isBlank() || state.equalsIgnoreCase(c.state()))
                .filter(c -> needle.isEmpty() || contains(c.instanceId(), needle) || contains(c.name(), needle) || contains(c.region(), needle))
                .toList();
        int s = Math.max(1, Math.min(size, 200));
        int p = Math.max(0, page);
        int from = Math.min(filtered.size(), p * s);
        return new XpPage<>(filtered.subList(from, Math.min(filtered.size(), from + s)), p, s, filtered.size());
    }

    private static boolean contains(String v, String needle) {
        return v != null && v.toLowerCase(Locale.ROOT).contains(needle);
    }

    @GetMapping("/buckets")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public List<XpBucketCandidate> buckets(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId) {
        return buckets.buckets(account(user, accountId));
    }

    @PostMapping("/setup-plan")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public XpSetupPlanResponse plan(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId,
                                    @RequestBody XpSetupPlanRequest body, HttpServletRequest request) {
        return setup.plan(account(user, accountId), body, user, XamPulseControllerSupport.ip(request));
    }

    @GetMapping("/stack-status")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public List<XpStackStatus> stackStatus(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId,
                                           @RequestParam(required = false) String region) {
        CloudAccount a = account(user, accountId);
        return watcher.refresh(a, a.getId()).stream()
                .filter(s -> region == null || region.isBlank() || region.equals(s.region())).toList();
    }

    @PostMapping("/verify")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public XpVerifyResponse verify(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId) {
        return new XpVerifyResponse(checklist.verify(account(user, accountId)));
    }

    @GetMapping(value = "/checklist/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize(XamPulseRoles.ADMIN)
    public SseEmitter stream(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId) {
        CloudAccount a = account(user, accountId);
        if (streams.incrementAndGet() > MAX_STREAMS) {
            streams.decrementAndGet();
            throw XamPulseException.rateLimited();
        }
        SseEmitter emitter = new SseEmitter(STREAM_CAP_MS);
        Map<String, XpChecklistCheck> last = new HashMap<>();
        ScheduledFuture<?>[] task = new ScheduledFuture<?>[1];
        Runnable release = () -> {
            if (task[0] != null) {
                task[0].cancel(false);
            }
        };
        emitter.onCompletion(() -> {
            release.run();
            streams.decrementAndGet();
        });
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitter.complete());
        task[0] = ticker.scheduleWithFixedDelay(() -> {
            try {
                List<XpChecklistCheck> checks = checklist.snapshot(a);
                for (XpChecklistCheck c : checks) {
                    if (!c.equals(last.put(c.id(), c))) {
                        emitter.send(SseEmitter.event().name("check").id(c.id()).data(c, MediaType.APPLICATION_JSON));
                    }
                }
                if (SetupChecklistService.isTerminal(checks)) {
                    emitter.send(SseEmitter.event().name("done").data("connected"));
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("XamPulse checklist stream failed for account {}: {}", accountId, e.toString());
                emitter.completeWithError(e);
            }
        }, 0, 3, TimeUnit.SECONDS);
        return emitter;
    }

    @PutMapping("/instances/{instanceId}/collection")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public XpInstanceCandidate collection(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId,
                                          @PathVariable String instanceId, @RequestBody XpCollectionUpdate body, HttpServletRequest request) {
        return setup.updateCollection(account(user, accountId), instanceId, body, user, XamPulseControllerSupport.ip(request));
    }

    @PostMapping("/regions/{region}/plan")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public XpStackLink regionPlan(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId, @PathVariable String region,
                                  @RequestBody XpRegionPlanRequest body, HttpServletRequest request) {
        return setup.regionPlan(account(user, accountId), region, body, user, XamPulseControllerSupport.ip(request));
    }

    @PostMapping("/disconnect")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public XpDisconnectResponse disconnect(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId,
                                           @RequestBody XpDisconnectRequest body, HttpServletRequest request) {
        return disconnect.disconnect(account(user, accountId), body, user, XamPulseControllerSupport.ip(request));
    }

    @GetMapping("/sources")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public List<XpLogSourceView> sources(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId) {
        return sources.list(account(user, accountId).getId());
    }

    @PutMapping("/sources")
    @PreAuthorize(XamPulseRoles.ADMIN)
    public List<XpLogSourceView> putSources(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId,
                                            @RequestBody List<XpLogSourceView> body) {
        return sources.replace(account(user, accountId).getId(), body);
    }
}
