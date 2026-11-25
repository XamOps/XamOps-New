package com.xammer.cloud.xampulse.service.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAlertRule;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAlertRuleRepository;
import com.xammer.cloud.xampulse.repository.XpAlertStateRepository;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** CRUD for alert rules. Every operation proves access to the rule's single account first. */
@Service("xpAlertRuleService")
public class AlertRuleService {

    private final XpAlertRuleRepository repo;
    private final XpAlertStateRepository states;
    private final XamPulseAccessGuard guard;
    private final RuleCompiler compiler;
    private final ObjectMapper mapper;

    public AlertRuleService(XpAlertRuleRepository repo, XpAlertStateRepository states, XamPulseAccessGuard guard,
                            RuleCompiler compiler, ObjectMapper objectMapper) {
        this.repo = repo;
        this.states = states;
        this.guard = guard;
        this.compiler = compiler;
        this.mapper = objectMapper;
    }

    public List<com.xammer.cloud.xampulse.dto.XpAlertRule> list(ClientUserDetails user, List<Long> accountIds) {
        List<com.xammer.cloud.xampulse.dto.XpAlertRule> out = new ArrayList<>();
        for (Long id : guard.requireAll(user, accountIds)) {
            repo.findByCloudAccountId(id).forEach(r -> out.add(toDto(mapper, r)));
        }
        return out;
    }

    @Transactional
    public com.xammer.cloud.xampulse.dto.XpAlertRule create(ClientUserDetails user, com.xammer.cloud.xampulse.dto.XpAlertRule in) {
        compiler.validate(in);
        Long accountId = in.scope().accountIds().get(0);
        guard.require(user, accountId);
        XpAlertRule e = new XpAlertRule();
        e.setCloudAccountId(accountId);
        e.setCreatedBy(user.getId());
        e.setCreatedAt(Instant.now());
        apply(e, in);
        return toDto(mapper, repo.save(e));
    }

    @Transactional
    public com.xammer.cloud.xampulse.dto.XpAlertRule update(ClientUserDetails user, Long id, com.xammer.cloud.xampulse.dto.XpAlertRule in) {
        compiler.validate(in);
        XpAlertRule e = find(user, id);
        if (!e.getCloudAccountId().equals(in.scope().accountIds().get(0))) {
            throw XamPulseException.invalid("A rule cannot be moved to another account.");
        }
        if (in.version() == null || in.version() != e.getVersion()) {
            throw XamPulseException.conflict("The rule was changed by someone else. Reload and try again.");
        }
        apply(e, in);
        try {
            return toDto(mapper, repo.saveAndFlush(e));
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            throw XamPulseException.conflict("The rule was changed by someone else. Reload and try again.");
        }
    }

    @Transactional
    public void delete(ClientUserDetails user, Long id) {
        XpAlertRule e = find(user, id);
        states.deleteByRuleId(e.getId());
        repo.deleteByIdAndCloudAccountId(e.getId(), e.getCloudAccountId());
    }

    private XpAlertRule find(ClientUserDetails user, Long id) {
        List<Long> accessible = guard.accessibleAwsAccounts(user).stream().map(a -> a.getId()).toList();
        return repo.findByIdAndCloudAccountIdIn(id, accessible).orElseThrow(XamPulseException::notFound);
    }

    private void apply(XpAlertRule e, com.xammer.cloud.xampulse.dto.XpAlertRule in) {
        e.setName(in.name().trim());
        e.setSeverity(in.severity());
        e.setEnabled(in.enabled());
        e.setUpdatedAt(Instant.now());
        try {
            // id/version live in columns; the JSON carries only the definition.
            e.setDefinitionJson(mapper.writeValueAsString(new com.xammer.cloud.xampulse.dto.XpAlertRule(null, null, in.name(),
                    in.enabled(), in.severity(), in.scope(), in.condition(), in.grouping(), in.routing())));
        } catch (JsonProcessingException ex) {
            throw XamPulseException.invalid("The rule could not be saved.");
        }
    }

    public static com.xammer.cloud.xampulse.dto.XpAlertRule toDto(ObjectMapper mapper, XpAlertRule e) {
        try {
            com.xammer.cloud.xampulse.dto.XpAlertRule d = mapper.readValue(e.getDefinitionJson(), com.xammer.cloud.xampulse.dto.XpAlertRule.class);
            return new com.xammer.cloud.xampulse.dto.XpAlertRule(e.getId(), e.getVersion(), e.getName(), e.isEnabled(), e.getSeverity(),
                    d.scope(), d.condition(), d.grouping(), d.routing());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored rule " + e.getId() + " could not be read");
        }
    }
}
