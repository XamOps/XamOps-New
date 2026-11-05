package com.xammer.cloud.service.envallocation;

import com.xammer.cloud.domain.envallocation.EnvAllocationRuleSet;
import com.xammer.cloud.domain.envallocation.EnvAllocationRuleVersion;
import com.xammer.cloud.repository.envallocation.EnvAllocationRuleSetRepository;
import com.xammer.cloud.repository.envallocation.EnvAllocationRuleVersionRepository;
import com.xammer.cloud.service.envallocation.AllocationModel.AllocationType;
import com.xammer.cloud.service.envallocation.AllocationModel.EnvironmentSpec;
import com.xammer.cloud.service.envallocation.AllocationModel.MatchField;
import com.xammer.cloud.service.envallocation.AllocationModel.MatchOperator;
import com.xammer.cloud.service.envallocation.AllocationModel.RuleSpec;
import com.xammer.cloud.service.envallocation.AllocationModel.SplitSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The client's rules: an editable draft, and the immutable versions published from it.
 *
 * <p>Only published versions are ever used for a stored run. The draft can be previewed against
 * any month without being published, which is how a rule is tried before it counts.
 */
@Service
public class EnvAllocationRuleService {

    static final int MAX_RULES = 200;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String ID_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EnvAllocationRuleSetRepository ruleSetRepository;
    private final EnvAllocationRuleVersionRepository versionRepository;
    private final EnvAllocationEnvironmentService environments;
    private final AllocationJson json;
    private final EnvAllocationAuditService audit;

    public EnvAllocationRuleService(EnvAllocationRuleSetRepository ruleSetRepository,
                                    EnvAllocationRuleVersionRepository versionRepository,
                                    EnvAllocationEnvironmentService environments,
                                    AllocationJson json,
                                    EnvAllocationAuditService audit) {
        this.ruleSetRepository = ruleSetRepository;
        this.versionRepository = versionRepository;
        this.environments = environments;
        this.json = json;
        this.audit = audit;
    }

    /**
     * The draft as the rule builder shows it.
     *
     * @param unpublishedChanges true when the draft differs from the latest published version
     */
    public record DraftView(List<RuleSpec> rules,
                            int latestVersion,
                            LocalDateTime draftUpdatedAt,
                            String draftUpdatedBy,
                            boolean unpublishedChanges) {
    }

    /** One published version, as the history list shows it. */
    public record VersionSummary(int versionNumber, LocalDateTime publishedAt, String publishedBy,
                                 String note, int ruleCount) {
    }

    /** One published version in full: its rules and the environments they named. */
    public record VersionSnapshot(Long id, int versionNumber, LocalDateTime publishedAt,
                                  String publishedBy, String note, List<RuleSpec> rules,
                                  List<EnvironmentSpec> environments) {
    }

    @Transactional
    public DraftView draft(Long clientId, String actor) {
        List<EnvironmentSpec> envs = environments.specs(clientId, actor);
        EnvAllocationRuleSet set = ruleSet(clientId);
        List<RuleSpec> rules = withCurrentNames(json.readRules(set.getDraftJson()), envs);
        return new DraftView(rules, set.getLatestVersion(), set.getDraftUpdatedAt(),
                set.getDraftUpdatedBy(), differsFromLatest(clientId, rules));
    }

    /** Replaces the draft with {@code rules}, in the order given. */
    @Transactional
    public DraftView saveDraft(Long clientId, String actor, List<RuleSpec> rules) {
        List<EnvironmentSpec> envs = environments.specs(clientId, actor);
        List<RuleSpec> clean = validate(rules, envs);

        EnvAllocationRuleSet set = ruleSet(clientId);
        set.setDraftJson(json.write(clean));
        set.setDraftUpdatedAt(LocalDateTime.now());
        set.setDraftUpdatedBy(actor);
        ruleSetRepository.save(set);
        audit.record(clientId, actor, "DRAFT_SAVED", "RULE_SET", null,
                clean.size() + " rule(s): " + json.write(clean));
        return new DraftView(clean, set.getLatestVersion(), set.getDraftUpdatedAt(),
                set.getDraftUpdatedBy(), differsFromLatest(clientId, clean));
    }

    /**
     * Freezes the saved draft as the next version.
     *
     * <p>Re-validated at publish rather than trusted from save: an environment the draft names
     * may have been deleted since, and a version that refers to nothing cannot be run.
     */
    @Transactional
    public VersionSummary publish(Long clientId, String actor, String note) {
        List<EnvironmentSpec> envs = environments.specs(clientId, actor);
        EnvAllocationRuleSet set = ruleSetRepository.findByClientIdForUpdate(clientId)
                .orElseThrow(() -> EnvAllocationException.conflict("There is no draft to publish."));
        List<RuleSpec> rules = validate(json.readRules(set.getDraftJson()), envs);
        if (rules.isEmpty()) {
            throw EnvAllocationException.badRequest("Add at least one rule before publishing.");
        }
        if (!differsFromLatest(clientId, rules)) {
            throw EnvAllocationException.conflict("The draft is the same as version "
                    + set.getLatestVersion() + ". Change a rule before publishing again.");
        }
        String cleanNote = note == null ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > 500) {
            throw EnvAllocationException.badRequest("A version note is at most 500 characters.");
        }

        int number = set.getLatestVersion() + 1;
        EnvAllocationRuleVersion version = new EnvAllocationRuleVersion();
        version.setClientId(clientId);
        version.setVersionNumber(number);
        version.setRulesJson(json.write(rules));
        version.setEnvironmentsJson(json.write(envs));
        version.setNote(cleanNote == null || cleanNote.isEmpty() ? null : cleanNote);
        version.setPublishedAt(LocalDateTime.now());
        version.setPublishedBy(actor);
        versionRepository.save(version);

        set.setLatestVersion(number);
        ruleSetRepository.save(set);
        audit.record(clientId, actor, "VERSION_PUBLISHED", "RULE_VERSION", String.valueOf(number),
                (version.getNote() == null ? "" : version.getNote() + " — ") + rules.size() + " rule(s)");
        return summary(version);
    }

    public List<VersionSummary> versions(Long clientId) {
        return versionRepository.findByClientIdOrderByVersionNumberDesc(clientId).stream()
                .map(this::summary)
                .toList();
    }

    public VersionSnapshot version(Long clientId, int versionNumber) {
        return versionRepository.findByClientIdAndVersionNumber(clientId, versionNumber)
                .map(this::snapshot)
                .orElseThrow(() -> EnvAllocationException.notFound("Version " + versionNumber + " not found."));
    }

    public java.util.Optional<VersionSnapshot> latest(Long clientId) {
        return versionRepository.findFirstByClientIdOrderByVersionNumberDesc(clientId).map(this::snapshot);
    }

    public VersionSnapshot versionById(Long versionId) {
        return versionRepository.findById(versionId)
                .map(this::snapshot)
                .orElseThrow(() -> EnvAllocationException.notFound("Rule version not found."));
    }

    /**
     * Rules sent for a preview, checked the same way as a save, without storing anything.
     *
     * <p>Null means "preview the saved draft".
     */
    @Transactional
    public List<RuleSpec> previewRules(Long clientId, String actor, List<RuleSpec> rules) {
        List<EnvironmentSpec> envs = environments.specs(clientId, actor);
        if (rules == null) {
            return withCurrentNames(json.readRules(ruleSet(clientId).getDraftJson()), envs);
        }
        return validate(rules, envs);
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    /**
     * Checks every rule and returns a clean copy: trimmed text, generated ids for new rules,
     * environment names filled in from the client's current list.
     */
    List<RuleSpec> validate(List<RuleSpec> rules, List<EnvironmentSpec> envs) {
        if (rules == null) {
            return List.of();
        }
        if (rules.size() > MAX_RULES) {
            throw EnvAllocationException.badRequest("At most " + MAX_RULES + " rules.");
        }
        Map<Long, EnvironmentSpec> envById = envs.stream()
                .collect(Collectors.toMap(EnvironmentSpec::id, Function.identity()));
        Set<String> ids = new HashSet<>();
        List<RuleSpec> out = new ArrayList<>(rules.size());

        for (int i = 0; i < rules.size(); i++) {
            RuleSpec r = rules.get(i);
            String where = "Rule " + (i + 1);
            if (r == null) {
                throw EnvAllocationException.badRequest(where + " is empty.");
            }
            String name = trim(r.name());
            if (name == null) {
                throw EnvAllocationException.badRequest(where + " needs a name.");
            }
            if (name.length() > 120) {
                throw EnvAllocationException.badRequest(where + ": the name is at most 120 characters.");
            }
            where = "Rule " + (i + 1) + " (\"" + name + "\")";

            MatchField field = r.field();
            if (field == null) {
                throw EnvAllocationException.badRequest(where + " needs something to match on.");
            }
            MatchOperator operator = field == MatchField.ANY ? MatchOperator.EXISTS : r.operator();
            if (operator == null) {
                throw EnvAllocationException.badRequest(where + " needs a comparison.");
            }
            String value = operator == MatchOperator.EXISTS ? null : trim(r.value());
            if (operator != MatchOperator.EXISTS && value == null) {
                throw EnvAllocationException.badRequest(where + " needs a value to compare with.");
            }
            if (value != null && value.length() > 255) {
                throw EnvAllocationException.badRequest(where + ": the value is at most 255 characters.");
            }
            String tagKey = field == MatchField.TAG ? trim(r.tagKey()) : null;
            if (field == MatchField.TAG && tagKey == null) {
                throw EnvAllocationException.badRequest(where + " needs a tag key.");
            }
            if (tagKey != null && tagKey.length() > 128) {
                throw EnvAllocationException.badRequest(where + ": a tag key is at most 128 characters.");
            }

            AllocationType allocation = r.allocation();
            if (allocation == null) {
                throw EnvAllocationException.badRequest(where + " needs an allocation.");
            }
            if (allocation == AllocationType.FROM_VALUE && field == MatchField.ANY) {
                throw EnvAllocationException.badRequest(where + ": \"use the matched value\" needs a"
                        + " field to read a value from, such as a tag.");
            }

            List<SplitSpec> splits = List.of();
            if (allocation == AllocationType.FIXED) {
                splits = validateSplits(where, r.splits(), envById);
            }

            String id = trim(r.id());
            if (id == null || id.length() > 40 || !ids.add(id)) {
                id = newId(ids);
            }
            out.add(new RuleSpec(id, name, field, operator, value, tagKey, allocation, splits));
        }
        return out;
    }

    private List<SplitSpec> validateSplits(String where, List<SplitSpec> splits,
                                           Map<Long, EnvironmentSpec> envById) {
        if (splits == null || splits.isEmpty()) {
            throw EnvAllocationException.badRequest(where + " needs at least one environment.");
        }
        Set<Long> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        List<SplitSpec> out = new ArrayList<>();
        for (SplitSpec s : splits) {
            if (s == null || s.environmentId() == null) {
                throw EnvAllocationException.badRequest(where + ": every share needs an environment.");
            }
            EnvironmentSpec env = envById.get(s.environmentId());
            if (env == null) {
                throw EnvAllocationException.badRequest(where + " names an environment that no longer"
                        + " exists. Pick another.");
            }
            if (!seen.add(env.id())) {
                throw EnvAllocationException.badRequest(where + " lists " + env.name() + " twice.");
            }
            BigDecimal p = s.percent();
            if (p == null || p.signum() <= 0 || p.compareTo(HUNDRED) > 0) {
                throw EnvAllocationException.badRequest(where + ": each share must be above 0% and at"
                        + " most 100%.");
            }
            if (p.stripTrailingZeros().scale() > 2) {
                throw EnvAllocationException.badRequest(where + ": percentages take at most two"
                        + " decimal places.");
            }
            sum = sum.add(p);
            out.add(new SplitSpec(env.id(), env.name(), p.setScale(2)));
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw EnvAllocationException.badRequest(where + ": shares add up to "
                    + sum.stripTrailingZeros().toPlainString() + "%, and must add up to 100%.");
        }
        return out;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private EnvAllocationRuleSet ruleSet(Long clientId) {
        return ruleSetRepository.findByClientId(clientId)
                .orElseThrow(() -> new IllegalStateException("Rule set missing after initialisation"));
    }

    /** Names in the draft are refreshed from the live list, so a rename shows straight away. */
    private static List<RuleSpec> withCurrentNames(List<RuleSpec> rules, List<EnvironmentSpec> envs) {
        Map<Long, EnvironmentSpec> envById = envs.stream()
                .collect(Collectors.toMap(EnvironmentSpec::id, Function.identity()));
        return rules.stream().map(r -> new RuleSpec(r.id(), r.name(), r.field(), r.operator(),
                r.value(), r.tagKey(), r.allocation(),
                r.splits() == null ? List.of() : r.splits().stream().map(s -> {
                    EnvironmentSpec env = envById.get(s.environmentId());
                    return new SplitSpec(s.environmentId(), env != null ? env.name() : s.environmentName(),
                            s.percent());
                }).toList())).toList();
    }

    /** Compared without environment names, so a rename alone is not an unpublished change. */
    private boolean differsFromLatest(Long clientId, List<RuleSpec> rules) {
        return versionRepository.findFirstByClientIdOrderByVersionNumberDesc(clientId)
                .map(v -> !comparable(json.readRules(v.getRulesJson())).equals(comparable(rules)))
                .orElse(!rules.isEmpty());
    }

    private String comparable(List<RuleSpec> rules) {
        return json.write(rules.stream().map(r -> new RuleSpec(r.id(), r.name(), r.field(),
                r.operator(), r.value(), r.tagKey(), r.allocation(),
                r.splits() == null ? List.of() : r.splits().stream()
                        .map(s -> new SplitSpec(s.environmentId(), null,
                                s.percent() == null ? null : s.percent().setScale(2)))
                        .toList())).toList());
    }

    private VersionSummary summary(EnvAllocationRuleVersion v) {
        return new VersionSummary(v.getVersionNumber(), v.getPublishedAt(), v.getPublishedBy(),
                v.getNote(), json.readRules(v.getRulesJson()).size());
    }

    private VersionSnapshot snapshot(EnvAllocationRuleVersion v) {
        return new VersionSnapshot(v.getId(), v.getVersionNumber(), v.getPublishedAt(),
                v.getPublishedBy(), v.getNote(), json.readRules(v.getRulesJson()),
                json.readEnvironments(v.getEnvironmentsJson()));
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String newId(Set<String> taken) {
        while (true) {
            StringBuilder sb = new StringBuilder("r_");
            for (int i = 0; i < 10; i++) {
                sb.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
            }
            if (taken.add(sb.toString())) {
                return sb.toString();
            }
        }
    }
}
