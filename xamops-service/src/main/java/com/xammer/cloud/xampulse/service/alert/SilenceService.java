package com.xammer.cloud.xampulse.service.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpSilenceMatcher;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpSilenceRepository;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Silences suppress notifications (not evaluation) for matching rules, instances or severities. */
@Service("xpSilenceService")
public class SilenceService {

    static final Duration MAX_SILENCE = Duration.ofDays(30);

    private final XpSilenceRepository repo;
    private final XamPulseAccessGuard guard;
    private final ObjectMapper mapper;

    public SilenceService(XpSilenceRepository repo, XamPulseAccessGuard guard, ObjectMapper objectMapper) {
        this.repo = repo;
        this.guard = guard;
        this.mapper = objectMapper;
    }

    public List<com.xammer.cloud.xampulse.dto.XpSilence> list(ClientUserDetails user, List<Long> accountIds) {
        List<com.xammer.cloud.xampulse.dto.XpSilence> out = new ArrayList<>();
        for (Long id : guard.requireAll(user, accountIds)) {
            repo.findByCloudAccountId(id).forEach(s -> out.add(toDto(s)));
        }
        return out;
    }

    public com.xammer.cloud.xampulse.dto.XpSilence create(ClientUserDetails user, com.xammer.cloud.xampulse.dto.XpSilence in) {
        guard.require(user, in.accountId());
        if (!in.endsAt().isAfter(in.startsAt())) {
            throw XamPulseException.invalid("A silence must end after it starts.");
        }
        if (Duration.between(in.startsAt(), in.endsAt()).compareTo(MAX_SILENCE) > 0) {
            throw XamPulseException.invalid("A silence can last at most 30 days.");
        }
        com.xammer.cloud.xampulse.domain.XpSilence e = new com.xammer.cloud.xampulse.domain.XpSilence();
        e.setCloudAccountId(in.accountId());
        e.setStartsAt(in.startsAt());
        e.setEndsAt(in.endsAt());
        e.setReason(in.reason());
        e.setCreatedBy(user.getId());
        try {
            e.setMatcherJson(mapper.writeValueAsString(in.matcher()));
        } catch (JsonProcessingException ex) {
            throw XamPulseException.invalid("Invalid silence matcher.");
        }
        return toDto(repo.save(e));
    }

    public void delete(ClientUserDetails user, Long id) {
        List<Long> accessible = guard.accessibleAwsAccounts(user).stream().map(a -> a.getId()).toList();
        com.xammer.cloud.xampulse.domain.XpSilence s = repo.findByIdAndCloudAccountIdIn(id, accessible)
                .orElseThrow(XamPulseException::notFound);
        repo.delete(s);
    }

    /** True when an active silence in the same account matches the rule/instance/severity. */
    public boolean isSilenced(Long accountId, Long ruleId, String instanceId, String severity, Instant now) {
        for (com.xammer.cloud.xampulse.domain.XpSilence s :
                repo.findByCloudAccountIdAndStartsAtBeforeAndEndsAtAfter(accountId, now, now)) {
            if (matches(parse(s.getMatcherJson()), ruleId, instanceId, severity)) {
                return true;
            }
        }
        return false;
    }

    /** An empty matcher matches nothing, so a malformed silence can never mute everything. */
    static boolean matches(XpSilenceMatcher m, Long ruleId, String instanceId, String severity) {
        if (m == null) {
            return false;
        }
        boolean hasInstances = m.instanceIds() != null && !m.instanceIds().isEmpty();
        boolean hasRules = m.ruleIds() != null && !m.ruleIds().isEmpty();
        boolean hasSev = m.severity() != null && !m.severity().isEmpty();
        if (!hasInstances && !hasRules && !hasSev) {
            return false;
        }
        return (!hasInstances || m.instanceIds().contains(instanceId))
                && (!hasRules || m.ruleIds().contains(ruleId))
                && (!hasSev || m.severity().contains(severity));
    }

    private XpSilenceMatcher parse(String json) {
        try {
            return json == null ? null : mapper.readValue(json, XpSilenceMatcher.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private com.xammer.cloud.xampulse.dto.XpSilence toDto(com.xammer.cloud.xampulse.domain.XpSilence s) {
        return new com.xammer.cloud.xampulse.dto.XpSilence(s.getId(), s.getCloudAccountId(), parse(s.getMatcherJson()),
                s.getStartsAt(), s.getEndsAt(), s.getReason());
    }
}
