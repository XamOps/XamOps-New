package com.xammer.cloud.xampulse.service.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpIncident;
import com.xammer.cloud.xampulse.domain.XpIncidentEvent;
import com.xammer.cloud.xampulse.dto.XpIncidentAction;
import com.xammer.cloud.xampulse.dto.XpPage;
import com.xammer.cloud.xampulse.dto.XpTimelineEntry;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpIncidentEventRepository;
import com.xammer.cloud.xampulse.repository.XpIncidentRepository;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Incident lifecycle: OPEN -> ACKED / SNOOZED -> RESOLVED. Reads and writes are scoped to accounts the caller can use. */
@Service("xpIncidentService")
public class IncidentService {

    static final Set<String> OPEN_STATES = Set.of("OPEN", "ACKED", "SNOOZED");
    static final Duration MAX_SNOOZE = Duration.ofDays(7);

    private final XpIncidentRepository incidents;
    private final XpIncidentEventRepository events;
    private final XamPulseAccessGuard guard;
    private final ObjectMapper mapper;

    public IncidentService(XpIncidentRepository incidents, XpIncidentEventRepository events,
                           XamPulseAccessGuard guard, ObjectMapper objectMapper) {
        this.incidents = incidents;
        this.events = events;
        this.guard = guard;
        this.mapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public XpPage<com.xammer.cloud.xampulse.dto.XpIncident> list(ClientUserDetails user, List<Long> accountIds, String state, int page, int size) {
        List<Long> ids = guard.requireAll(user, accountIds);
        PageRequest pr = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size <= 0 ? 50 : size, 200)),
                Sort.by(Sort.Direction.DESC, "openedAt"));
        org.springframework.data.domain.Page<XpIncident> res;
        if (state == null || state.isBlank()) {
            res = incidents.findByCloudAccountIdIn(ids, pr);
        } else {
            String s = state.toUpperCase(Locale.ROOT);
            if (!Set.of("OPEN", "ACKED", "SNOOZED", "RESOLVED").contains(s)) {
                throw XamPulseException.invalid("Unknown state.");
            }
            res = incidents.findByCloudAccountIdInAndStateIn(ids, List.of(s), pr);
        }
        return new XpPage<>(res.getContent().stream().map(i -> toDto(i, false)).toList(), res.getNumber(), res.getSize(), res.getTotalElements());
    }

    @Transactional(readOnly = true)
    public com.xammer.cloud.xampulse.dto.XpIncident get(ClientUserDetails user, Long id) {
        return toDto(find(user, id), true);
    }

    @Transactional
    public com.xammer.cloud.xampulse.dto.XpIncident act(ClientUserDetails user, Long id, String action, XpIncidentAction body) {
        XpIncident i = find(user, id);
        Instant now = Instant.now();
        String who = user.getUsername();
        switch (action) {
            case "ack" -> {
                requireNotResolved(i);
                i.setState("ACKED");
                i.setAckBy(who);
                i.setSnoozedUntil(null);
                addEvent(i.getId(), "ACK", who + " acknowledged the incident");
            }
            case "snooze" -> {
                requireNotResolved(i);
                Instant until = body == null ? null : body.snoozeUntil();
                if (until == null || !until.isAfter(now) || Duration.between(now, until).compareTo(MAX_SNOOZE) > 0) {
                    throw XamPulseException.invalid("Choose a snooze end within the next 7 days.");
                }
                i.setState("SNOOZED");
                i.setSnoozedUntil(until);
                addEvent(i.getId(), "SNOOZE", who + " snoozed until " + until);
            }
            case "resolve" -> {
                requireNotResolved(i);
                i.setState("RESOLVED");
                i.setResolvedAt(now);
                addEvent(i.getId(), "RESOLVE", who + " resolved the incident");
            }
            case "comment" -> {
                if (body == null || body.comment() == null || body.comment().isBlank()) {
                    throw XamPulseException.invalid("A comment is required.");
                }
                addEvent(i.getId(), "COMMENT", who + ": " + body.comment().trim());
            }
            default -> throw XamPulseException.notFound();
        }
        return toDto(incidents.save(i), true);
    }

    public List<com.xammer.cloud.xampulse.dto.XpIncident> openForInstance(Long accountId, String instanceId) {
        List<com.xammer.cloud.xampulse.dto.XpIncident> out = new ArrayList<>();
        for (XpIncident i : incidents.findByCloudAccountIdAndStateIn(accountId, OPEN_STATES)) {
            Summary s = summary(i);
            if (s.instanceIds.contains(instanceId)) {
                out.add(toDto(i, false));
            }
        }
        return out;
    }

    /** Open (not acknowledged, not snoozed) incidents for an account; callers have already authorised the account. */
    public List<com.xammer.cloud.xampulse.dto.XpIncident> activeFor(Long accountId) {
        return incidents.findByCloudAccountIdAndStateIn(accountId, Set.of("OPEN")).stream().map(i -> toDto(i, false)).toList();
    }

    public long openCount(Long accountId) {
        return incidents.countByCloudAccountIdAndStateIn(accountId, OPEN_STATES);
    }

    // ---- used by the evaluator (system context, account already established by the rule) ----

    @Transactional
    public XpIncident openOrJoin(Long accountId, String severity, String title, Long ruleId, String instanceId,
                                 String text, IncidentCorrelator correlator, Instant now) {
        List<XpIncident> open = incidents.findByCloudAccountIdAndStateIn(accountId, OPEN_STATES);
        List<IncidentCorrelator.OpenIncident> views = new ArrayList<>();
        for (XpIncident i : open) {
            Instant last = events.findByIncidentIdOrderByTsAsc(i.getId()).stream().map(XpIncidentEvent::getTs)
                    .max(Instant::compareTo).orElse(i.getOpenedAt());
            views.add(new IncidentCorrelator.OpenIncident(i.getId(), new ArrayList<>(summary(i).instanceIds), last));
        }
        var picked = correlator.pick(views, instanceId, now);
        if (picked.isPresent()) {
            XpIncident i = open.stream().filter(x -> x.getId() == picked.get().id()).findFirst().orElseThrow();
            Summary s = summary(i);
            s.ruleIds.add(ruleId);
            if (instanceId != null) {
                s.instanceIds.add(instanceId);
            }
            i.setSummaryJson(write(s));
            if (rank(severity) > rank(i.getSeverity())) {
                i.setSeverity(severity);
            }
            addEvent(i.getId(), "FIRING", text);
            return incidents.save(i);
        }
        XpIncident i = new XpIncident();
        i.setCloudAccountId(accountId);
        i.setTitle(title);
        i.setSeverity(severity);
        i.setState("OPEN");
        i.setOpenedAt(now);
        Summary s = new Summary();
        s.ruleIds.add(ruleId);
        if (instanceId != null) {
            s.instanceIds.add(instanceId);
        }
        s.text = text;
        i.setSummaryJson(write(s));
        i = incidents.save(i);
        addEvent(i.getId(), "OPENED", text);
        return i;
    }

    @Transactional
    public void recordResolution(Long accountId, Long ruleId, String instanceId, String text) {
        for (XpIncident i : incidents.findByCloudAccountIdAndStateIn(accountId, OPEN_STATES)) {
            Summary s = summary(i);
            if (s.ruleIds.contains(ruleId) && (instanceId == null || s.instanceIds.contains(instanceId))) {
                addEvent(i.getId(), "RECOVERED", text);
            }
        }
    }

    private void requireNotResolved(XpIncident i) {
        if ("RESOLVED".equals(i.getState())) {
            throw XamPulseException.conflict("The incident is already resolved.");
        }
    }

    private XpIncident find(ClientUserDetails user, Long id) {
        List<Long> accessible = guard.accessibleAwsAccounts(user).stream().map(a -> a.getId()).toList();
        return incidents.findByIdAndCloudAccountIdIn(id, accessible).orElseThrow(XamPulseException::notFound);
    }

    private void addEvent(Long incidentId, String kind, String text) {
        XpIncidentEvent e = new XpIncidentEvent();
        e.setIncidentId(incidentId);
        e.setTs(Instant.now());
        e.setKind(kind);
        e.setPayloadJson(text);
        events.save(e);
    }

    static int rank(String sev) {
        return switch (sev == null ? "" : sev) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    static final class Summary {
        public Set<Long> ruleIds = new LinkedHashSet<>();
        public Set<String> instanceIds = new LinkedHashSet<>();
        public String text = "";
    }

    private Summary summary(XpIncident i) {
        try {
            return i.getSummaryJson() == null ? new Summary() : mapper.readValue(i.getSummaryJson(), Summary.class);
        } catch (JsonProcessingException e) {
            return new Summary();
        }
    }

    private String write(Summary s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write incident summary");
        }
    }

    private com.xammer.cloud.xampulse.dto.XpIncident toDto(XpIncident i, boolean withTimeline) {
        Summary s = summary(i);
        List<XpTimelineEntry> timeline = withTimeline
                ? events.findByIncidentIdOrderByTsAsc(i.getId()).stream().map(e -> new XpTimelineEntry(e.getTs(), e.getKind(), e.getPayloadJson())).toList()
                : List.of();
        return new com.xammer.cloud.xampulse.dto.XpIncident(i.getId(), i.getCloudAccountId(), i.getTitle(), i.getSeverity(),
                i.getState(), i.getOpenedAt(), i.getResolvedAt(), i.getAckBy(), new ArrayList<>(s.ruleIds),
                new ArrayList<>(s.instanceIds), s.text, timeline);
    }
}
