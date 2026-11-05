package com.xammer.cloud.service.envallocation;

import com.xammer.cloud.domain.envallocation.EnvAllocationEnvironment;
import com.xammer.cloud.domain.envallocation.EnvAllocationRuleSet;
import com.xammer.cloud.repository.envallocation.EnvAllocationEnvironmentRepository;
import com.xammer.cloud.repository.envallocation.EnvAllocationRuleSetRepository;
import com.xammer.cloud.service.envallocation.AllocationModel.EnvironmentSpec;
import com.xammer.cloud.service.envallocation.AllocationModel.RuleSpec;
import com.xammer.cloud.service.envallocation.AllocationModel.SplitSpec;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A client's environment list: seeded with defaults on first use, then theirs to edit.
 *
 * <p>Seeding happens once per client, keyed on the rule set row it creates alongside. A client who
 * deletes every default therefore gets an empty list back, not the defaults again.
 */
@Service
public class EnvAllocationEnvironmentService {

    /** The starting list. Match values cover the spellings an {@code Environment} tag usually has. */
    static final List<Default> DEFAULTS = List.of(
            new Default("Prod", "#DC2626", "prod,production,prd,live"),
            new Default("UAT", "#F59E0B", "uat,qa,test,testing"),
            new Default("Staging", "#8B5CF6", "staging,stage,stg,preprod"),
            new Default("R&D", "#2563EB", "rnd,r&d,dev,development,research"),
            new Default("SaaS", "#059669", "saas"));

    record Default(String name, String color, String matchValues) {
    }

    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final int MAX_ENVIRONMENTS = 30;

    private final EnvAllocationEnvironmentRepository repository;
    private final EnvAllocationRuleSetRepository ruleSetRepository;
    private final AllocationJson json;
    private final EnvAllocationAuditService audit;
    private final TransactionTemplate seedTx;

    public EnvAllocationEnvironmentService(EnvAllocationEnvironmentRepository repository,
                                           EnvAllocationRuleSetRepository ruleSetRepository,
                                           AllocationJson json,
                                           EnvAllocationAuditService audit,
                                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.ruleSetRepository = ruleSetRepository;
        this.json = json;
        this.audit = audit;
        this.seedTx = new TransactionTemplate(transactionManager);
        this.seedTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The client's environments in display order, seeding the defaults the first time. */
    @Transactional
    public List<EnvAllocationEnvironment> list(Long clientId, String actor) {
        ensureInitialised(clientId, actor);
        return repository.findByClientIdOrderBySortOrderAscIdAsc(clientId);
    }

    /** The same list in the shape rules and versions use. */
    @Transactional
    public List<EnvironmentSpec> specs(Long clientId, String actor) {
        return list(clientId, actor).stream().map(EnvAllocationEnvironmentService::toSpec).toList();
    }

    @Transactional
    public EnvAllocationEnvironment create(Long clientId, String actor, String name, String color,
                                           List<String> matchValues) {
        List<EnvAllocationEnvironment> existing = list(clientId, actor);
        if (existing.size() >= MAX_ENVIRONMENTS) {
            throw EnvAllocationException.badRequest("A client can have at most " + MAX_ENVIRONMENTS
                    + " environments.");
        }
        String cleanName = cleanName(name);
        requireUniqueName(existing, cleanName, null);

        EnvAllocationEnvironment env = new EnvAllocationEnvironment();
        env.setClientId(clientId);
        env.setName(cleanName);
        env.setColor(cleanColor(color));
        env.setMatchValues(cleanMatchValues(matchValues));
        env.setSortOrder(existing.stream().mapToInt(EnvAllocationEnvironment::getSortOrder).max().orElse(0) + 1);
        env.setCreatedAt(LocalDateTime.now());
        env.setCreatedBy(actor);
        EnvAllocationEnvironment saved = save(env);
        audit.record(clientId, actor, "ENVIRONMENT_CREATED", "ENVIRONMENT", String.valueOf(saved.getId()),
                describe(saved));
        return saved;
    }

    @Transactional
    public EnvAllocationEnvironment update(Long clientId, String actor, Long id, String name,
                                           String color, List<String> matchValues, Integer sortOrder) {
        EnvAllocationEnvironment env = repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> EnvAllocationException.notFound("Environment not found."));
        String before = describe(env);
        String cleanName = cleanName(name);
        requireUniqueName(repository.findByClientIdOrderBySortOrderAscIdAsc(clientId), cleanName, id);

        env.setName(cleanName);
        env.setColor(cleanColor(color));
        env.setMatchValues(cleanMatchValues(matchValues));
        if (sortOrder != null) {
            env.setSortOrder(sortOrder);
        }
        env.setUpdatedAt(LocalDateTime.now());
        EnvAllocationEnvironment saved = save(env);
        audit.record(clientId, actor, "ENVIRONMENT_UPDATED", "ENVIRONMENT", String.valueOf(id),
                before + " -> " + describe(saved));
        return saved;
    }

    /**
     * Deletes an environment no draft rule sends money to.
     *
     * <p>Published versions are unaffected: they carry their own copy of the environment, so a
     * past run still reads the name it was produced with.
     */
    @Transactional
    public void delete(Long clientId, String actor, Long id) {
        EnvAllocationEnvironment env = repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> EnvAllocationException.notFound("Environment not found."));

        List<String> using = new ArrayList<>();
        ruleSetRepository.findByClientId(clientId).ifPresent(set -> {
            for (RuleSpec rule : json.readRules(set.getDraftJson())) {
                List<SplitSpec> splits = rule.splits() == null ? List.of() : rule.splits();
                if (splits.stream().anyMatch(s -> id.equals(s.environmentId()))) {
                    using.add(rule.name());
                }
            }
        });
        if (!using.isEmpty()) {
            throw EnvAllocationException.conflict("\"" + env.getName() + "\" is used by "
                    + (using.size() == 1 ? "the rule " : "the rules ") + String.join(", ", using)
                    + ". Change those rules first.");
        }
        repository.delete(env);
        audit.record(clientId, actor, "ENVIRONMENT_DELETED", "ENVIRONMENT", String.valueOf(id), describe(env));
    }

    // ── Seeding ─────────────────────────────────────────────────────────────────

    /**
     * Creates the client's rule set row and default environments, once.
     *
     * <p>In its own transaction: two first requests racing would otherwise both insert, and the
     * loser's unique-constraint failure would abort the caller's transaction along with it. Here
     * the loser's insert fails on its own and the caller carries on reading what the winner wrote.
     */
    public void ensureInitialised(Long clientId, String actor) {
        if (ruleSetRepository.findByClientId(clientId).isPresent()) {
            return;
        }
        try {
            seedTx.executeWithoutResult(status -> seed(clientId, actor));
        } catch (DataIntegrityViolationException raced) {
            // Another request seeded this client first.
        }
    }

    private void seed(Long clientId, String actor) {
        EnvAllocationRuleSet set = new EnvAllocationRuleSet();
        set.setClientId(clientId);
        set.setDraftJson("[]");
        set.setLatestVersion(0);
        ruleSetRepository.saveAndFlush(set);
        if (repository.countByClientId(clientId) > 0) {
            return;
        }
        int order = 1;
        for (Default d : DEFAULTS) {
            EnvAllocationEnvironment env = new EnvAllocationEnvironment();
            env.setClientId(clientId);
            env.setName(d.name());
            env.setColor(d.color());
            env.setMatchValues(d.matchValues());
            env.setSortOrder(order++);
            env.setCreatedAt(LocalDateTime.now());
            env.setCreatedBy("system");
            repository.save(env);
        }
        audit.record(clientId, actor, "ENVIRONMENTS_SEEDED", "ENVIRONMENT", null,
                "Default environments created: "
                        + String.join(", ", DEFAULTS.stream().map(Default::name).toList()));
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    static EnvironmentSpec toSpec(EnvAllocationEnvironment env) {
        return new EnvironmentSpec(env.getId(), env.getName(), env.getColor(), splitValues(env.getMatchValues()));
    }

    static List<String> splitValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    private EnvAllocationEnvironment save(EnvAllocationEnvironment env) {
        try {
            return repository.saveAndFlush(env);
        } catch (DataIntegrityViolationException e) {
            throw EnvAllocationException.conflict("An environment named \"" + env.getName()
                    + "\" already exists.");
        }
    }

    private static void requireUniqueName(List<EnvAllocationEnvironment> existing, String name, Long selfId) {
        for (EnvAllocationEnvironment e : existing) {
            if (!e.getId().equals(selfId) && e.getName().equalsIgnoreCase(name)) {
                throw EnvAllocationException.conflict("An environment named \"" + e.getName()
                        + "\" already exists.");
            }
        }
        if (AllocationModel.UNALLOCATED.equalsIgnoreCase(name)) {
            throw EnvAllocationException.badRequest("\"" + AllocationModel.UNALLOCATED
                    + "\" is reserved for spend no rule matched.");
        }
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw EnvAllocationException.badRequest("An environment needs a name.");
        }
        if (n.length() > 60) {
            throw EnvAllocationException.badRequest("An environment name is at most 60 characters.");
        }
        return n;
    }

    private static String cleanColor(String color) {
        if (color == null || color.isBlank()) {
            return "#6B7280";
        }
        if (!COLOR.matcher(color.trim()).matches()) {
            throw EnvAllocationException.badRequest("Colour must be a hex value like #2563EB.");
        }
        return color.trim().toUpperCase(Locale.ROOT);
    }

    private static String cleanMatchValues(List<String> values) {
        if (values == null) {
            return null;
        }
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String v : values) {
            if (v == null) {
                continue;
            }
            String t = v.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.contains(",")) {
                throw EnvAllocationException.badRequest("A match value cannot contain a comma.");
            }
            clean.add(t);
        }
        String joined = String.join(",", clean);
        if (joined.length() > 500) {
            throw EnvAllocationException.badRequest("Match values are at most 500 characters in total.");
        }
        return joined.isEmpty() ? null : joined;
    }

    private static String describe(EnvAllocationEnvironment env) {
        return env.getName() + " " + env.getColor()
                + (env.getMatchValues() == null ? "" : " [" + env.getMatchValues() + "]");
    }
}
