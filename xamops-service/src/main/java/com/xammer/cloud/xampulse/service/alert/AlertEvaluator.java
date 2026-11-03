package com.xammer.cloud.xampulse.service.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.xampulse.domain.XpAlertRule;
import com.xammer.cloud.xampulse.domain.XpAlertState;
import com.xammer.cloud.xampulse.domain.XpIncident;
import com.xammer.cloud.xampulse.dto.XpCondition;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAlertRuleRepository;
import com.xammer.cloud.xampulse.repository.XpAlertStateRepository;
import com.xammer.cloud.xampulse.service.alert.channel.Notification;
import com.xammer.cloud.xampulse.service.insight.EventWriter;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Evaluates every enabled rule once a minute. Each rule runs inside its own tenant, in its own try/catch, on a
 * small bounded pool. A rule that cannot read the store moves its groups to ERROR (never to OK).
 */
@Service("xpAlertEvaluator")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);
    static final String NO_BASELINE = "no baseline yet";

    private final XpAlertRuleRepository rules;
    private final XpAlertStateRepository states;
    private final ConditionEvaluator conditions;
    private final IncidentService incidents;
    private final IncidentCorrelator correlator;
    private final SilenceService silences;
    private final NotificationRouter router;
    private final SchedulingEnvironmentConfig scheduling;
    private final ObjectMapper mapper;
    /** Optional: when present, alert/anomaly lifecycle transitions are mirrored into the xp_events timeline. */
    @Autowired(required = false)
    private EventWriter eventWriter;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "xp-alert-eval");
        t.setDaemon(true);
        return t;
    });

    public AlertEvaluator(XpAlertRuleRepository rules, XpAlertStateRepository states, ConditionEvaluator conditions,
                          IncidentService incidents, IncidentCorrelator correlator, SilenceService silences,
                          NotificationRouter router, SchedulingEnvironmentConfig scheduling, ObjectMapper mapper) {
        this.rules = rules;
        this.states = states;
        this.conditions = conditions;
        this.incidents = incidents;
        this.correlator = correlator;
        this.silences = silences;
        this.router = router;
        this.scheduling = scheduling;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        if (!scheduling.isSchedulingEnabled()) {
            return;
        }
        evaluateAll(Instant.now());
    }

    public void evaluateAll(Instant now) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (XpAlertRule r : rules.findAllEnabledForScheduler()) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    evaluateRule(r, now);
                } catch (RuntimeException e) {
                    log.warn("XamPulse rule {} evaluation failed: {}", r.getId(), e.getClass().getSimpleName());
                }
            }, pool));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(50, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("XamPulse alert evaluation round did not finish in time: {}", e.getClass().getSimpleName());
        }
    }

    void evaluateRule(XpAlertRule entity, Instant now) {
        com.xammer.cloud.xampulse.dto.XpAlertRule rule = AlertRuleService.toDto(mapper, entity);
        Long accountId = entity.getCloudAccountId();
        if (rule.scope() == null || rule.scope().accountIds() == null || rule.scope().accountIds().size() != 1
                || !accountId.equals(rule.scope().accountIds().get(0))) {
            // Stored scope must match the owning tenant; anything else is not evaluated.
            log.warn("XamPulse rule {} skipped: scope does not match its account", entity.getId());
            return;
        }
        XpCondition cond = rule.condition();
        AlertStateMachine.NoDataPolicy policy = cond.noData() == null ? AlertStateMachine.NoDataPolicy.KEEP
                : AlertStateMachine.NoDataPolicy.valueOf(cond.noData());
        Duration hold = RuleCompiler.hold(cond);

        Map<String, XpAlertState> existing = new HashMap<>();
        states.findByRuleId(entity.getId()).forEach(s -> existing.put(s.getGroupKey(), s));

        List<ConditionEvaluator.Observation> observations;
        try {
            observations = conditions.observe(accountId, rule, now);
        } catch (XamPulseException e) {
            for (XpAlertState s : existing.values()) {
                apply(entity, rule, s, AlertStateMachine.Outcome.ERROR, policy, hold, now, null);
            }
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ConditionEvaluator.Observation o : observations) {
            seen.add(o.group());
            XpAlertState s = existing.computeIfAbsent(o.group(), g -> fresh(entity.getId(), g, now));
            AlertStateMachine.Outcome out = o.value() == null || NO_BASELINE.equals(o.detail())
                    ? AlertStateMachine.Outcome.NO_DATA
                    : (o.breaching() ? AlertStateMachine.Outcome.BREACH : AlertStateMachine.Outcome.OK);
            apply(entity, rule, s, out, policy, hold, now, o);
        }
        for (XpAlertState s : new ArrayList<>(existing.values())) {
            if (!seen.contains(s.getGroupKey())) {
                apply(entity, rule, s, AlertStateMachine.Outcome.NO_DATA, policy, hold, now, null);
            }
        }
    }

    private void apply(XpAlertRule entity, com.xammer.cloud.xampulse.dto.XpAlertRule rule, XpAlertState s,
                       AlertStateMachine.Outcome outcome, AlertStateMachine.NoDataPolicy policy, Duration hold,
                       Instant now, ConditionEvaluator.Observation obs) {
        AlertStateMachine.Current cur = new AlertStateMachine.Current(AlertStateMachine.State.valueOf(s.getState()), s.getSince());
        AlertStateMachine.Transition t = AlertStateMachine.step(cur, outcome, policy, hold, now);
        s.setState(t.state().name());
        s.setSince(t.since());
        s.setLastEvalAt(now);
        if (obs != null && obs.value() != null) {
            s.setLastValue(obs.value());
        }
        states.save(s);
        if (t.fire() || t.quietReopen()) {
            onFiring(entity, rule, s.getGroupKey(), obs, now, !t.quietReopen());
        } else if (t.resolve()) {
            String instance = instanceOf(s.getGroupKey());
            incidents.recordResolution(entity.getCloudAccountId(), entity.getId(), instance, rule.name() + " recovered on " + instance);
            notify("RESOLVED", entity, rule, s.getGroupKey(), instance, rule.name() + " recovered", null, now, null);
            emitEvent(entity, rule, instance, rule.name() + " recovered", null, now);
        }
    }

    private void onFiring(XpAlertRule entity, com.xammer.cloud.xampulse.dto.XpAlertRule rule, String group,
                          ConditionEvaluator.Observation obs, Instant now, boolean notifyNow) {
        String instance = instanceOf(group);
        String text = rule.name() + " is firing on " + instance + (obs != null && obs.value() != null
                ? " (value " + String.format(java.util.Locale.ROOT, "%.2f", obs.value()) + ")" : "")
                + (obs != null && obs.detail() != null ? ". " + obs.detail() : "");
        XpIncident inc = incidents.openOrJoin(entity.getCloudAccountId(), rule.severity(), rule.name(), entity.getId(),
                instance, text, correlator, now);
        if (notifyNow) {
            notify("FIRED", entity, rule, group, instance, text, inc.getId(), now, rule.severity());
        }
        emitEvent(entity, rule, instance, rule.name() + " firing", text, now);
    }

    /**
     * Mirrors an alert/anomaly lifecycle transition into the xp_events timeline. Best-effort: a missing writer or a
     * ClickHouse error must never fail evaluation. METRIC_ANOMALY rules record an ANOMALY event, everything else ALERT.
     */
    private void emitEvent(XpAlertRule entity, com.xammer.cloud.xampulse.dto.XpAlertRule rule, String instance,
                           String title, String detail, Instant now) {
        if (eventWriter == null) {
            return;
        }
        try {
            boolean anomaly = rule.condition() != null && "METRIC_ANOMALY".equals(rule.condition().type());
            eventWriter.record(entity.getCloudAccountId(), anomaly ? "ANOMALY" : "ALERT", instance, title, detail, now);
        } catch (Exception e) {
            log.warn("XamPulse event emission failed for rule {}: {}", entity.getId(), e.getClass().getSimpleName());
        }
    }

    /** Test seam for injecting the optional event writer. */
    void setEventWriter(EventWriter eventWriter) {
        this.eventWriter = eventWriter;
    }

    private void notify(String kind, XpAlertRule entity, com.xammer.cloud.xampulse.dto.XpAlertRule rule, String group,
                        String instance, String text, Long incidentId, Instant now, String severityOverride) {
        boolean silenced = silences.isSilenced(entity.getCloudAccountId(), entity.getId(), instance, rule.severity(), now);
        Duration dedupe = rule.grouping() != null && rule.grouping().dedupeWindow() != null
                ? DurationSpec.parse(rule.grouping().dedupeWindow(), "dedupeWindow") : Duration.ofMinutes(15);
        var channelIds = rule.routing() == null ? List.<Long>of() : rule.routing().channelIds();
        String link = "/xampulse/alerts" + (incidentId == null ? "" : "?incident=" + incidentId);
        router.route(entity.getCloudAccountId(), entity.getId(), group, dedupe, channelIds,
                new Notification(kind, rule.name(), text, rule.severity(), entity.getCloudAccountId(), instance, incidentId, link, now),
                silenced, now);
    }

    private static XpAlertState fresh(Long ruleId, String group, Instant now) {
        XpAlertState s = new XpAlertState();
        s.setRuleId(ruleId);
        s.setGroupKey(group);
        s.setState("OK");
        s.setSince(now);
        return s;
    }

    static String instanceOf(String group) {
        int bar = group.indexOf('|');
        return bar < 0 ? group : group.substring(0, bar);
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
