package com.xammer.cloud.xampulse.service.setup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.domain.XpLogSource;
import com.xammer.cloud.xampulse.dto.XpLogSourceView;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.repository.XpLogSourceRepository;
import com.xammer.cloud.xampulse.service.agent.RedactionDefaults;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Log sources of an account (what agents collect) and their validation. Replacing sources makes agents reload. */
@Service("xpLogSourceService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class LogSourceService {

    static final int MAX_SOURCES = 20;
    static final int MAX_PATHS = 20;
    static final int MAX_REDACTIONS = 20;
    private static final Pattern PARSER = Pattern.compile("^[A-Za-z0-9_.-]{0,64}$");
    private static final Pattern RULE_NAME = Pattern.compile("^[A-Za-z0-9_. -]{1,64}$");
    private static final Set<String> KINDS = Set.of("SYSTEM", "DOCKER", "CUSTOM");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final XpLogSourceRepository sources;
    private final XpAgentRepository agents;

    public LogSourceService(XpLogSourceRepository sources, XpAgentRepository agents) {
        this.sources = sources;
        this.agents = agents;
    }

    @Transactional(readOnly = true)
    public List<XpLogSourceView> list(Long accountId) {
        return sources.findByCloudAccountId(accountId).stream().map(LogSourceService::toView).toList();
    }

    /** Replaces the account's sources and asks every agent to reload its configuration. */
    @Transactional
    public List<XpLogSourceView> replace(Long accountId, List<XpLogSourceView> views) {
        List<String> errors = validate(views);
        if (!errors.isEmpty()) {
            throw new XamPulseException("XP_INVALID_REQUEST", 400, errors.get(0), java.util.Map.of("errors", errors));
        }
        sources.deleteByCloudAccountId(accountId);
        sources.flush();
        List<XpLogSourceView> out = new ArrayList<>();
        for (XpLogSourceView v : views) {
            XpLogSource e = new XpLogSource();
            e.setCloudAccountId(accountId);
            e.setKind(v.kind());
            e.setPathsJson(write(v.paths() == null ? List.of() : v.paths()));
            e.setParser(v.parser());
            e.setEnabled(v.enabled());
            e.setRedactionJson(v.redaction() == null || v.redaction().isEmpty() ? null : write(v.redaction()));
            out.add(toView(sources.save(e)));
        }
        for (XpAgent a : agents.findByCloudAccountId(accountId)) {
            if ("ACTIVE".equals(a.getState()) || "PENDING".equals(a.getState())) {
                a.setConfigVersion(a.getConfigVersion() + 1);
                if (!"UNINSTALL".equals(a.getPendingCommand())) {
                    a.setPendingCommand("RELOAD_CONFIG");
                }
                agents.save(a);
            }
        }
        return out;
    }

    public static List<String> validate(List<XpLogSourceView> views) {
        List<String> errors = new ArrayList<>();
        if (views == null) {
            errors.add("A list of sources is required.");
            return errors;
        }
        if (views.size() > MAX_SOURCES) {
            errors.add("At most " + MAX_SOURCES + " sources.");
        }
        for (XpLogSourceView v : views) {
            if (v == null || v.kind() == null || !KINDS.contains(v.kind())) {
                errors.add("Source kind must be SYSTEM, DOCKER or CUSTOM.");
                continue;
            }
            List<String> paths = v.paths() == null ? List.of() : v.paths();
            if (paths.size() > MAX_PATHS) {
                errors.add("At most " + MAX_PATHS + " paths per source.");
            }
            if ("CUSTOM".equals(v.kind()) && paths.isEmpty()) {
                errors.add("A custom source needs at least one path.");
            }
            for (String p : paths) {
                if (!validPath(p)) {
                    errors.add("Log path " + abbreviate(p) + " must be absolute, without '..', and not point at credentials or XamPulse state.");
                }
            }
            if (v.parser() != null && !PARSER.matcher(v.parser()).matches()) {
                errors.add("Parser name contains characters that are not allowed.");
            }
            List<XpLogSourceView.RedactionRule> red = v.redaction() == null ? List.of() : v.redaction();
            if (red.size() > MAX_REDACTIONS) {
                errors.add("At most " + MAX_REDACTIONS + " redaction rules per source.");
            }
            for (XpLogSourceView.RedactionRule r : red) {
                if (r == null || r.rule() == null || !RULE_NAME.matcher(r.rule()).matches()) {
                    errors.add("Redaction rule names must be 1 to 64 plain characters.");
                    continue;
                }
                String bad = RedactionDefaults.validateCustomPattern(r.pattern());
                if (bad != null) {
                    errors.add(r.rule() + ": " + bad);
                }
            }
        }
        return errors;
    }

    /** Same path rules as the agent renderer, so the server never stores a path the agent will refuse. */
    static boolean validPath(String p) {
        if (p == null || !p.startsWith("/") || p.length() > 512 || p.contains("..") || p.contains("\n") || p.contains("\r") || p.contains("\u0000")) {
            return false;
        }
        for (String banned : List.of("/var/lib/xampulse", "/.ssh/", "/etc/shadow", "/etc/ssl/private", "/proc/", "/sys/")) {
            if (p.contains(banned)) {
                return false;
            }
        }
        return true;
    }

    private static String abbreviate(String s) {
        return s == null ? "(empty)" : (s.length() > 60 ? s.substring(0, 60) + "..." : s);
    }

    public static XpLogSourceView toView(XpLogSource e) {
        List<String> paths = read(e.getPathsJson(), new TypeReference<List<String>>() {});
        List<XpLogSourceView.RedactionRule> red = read(e.getRedactionJson(), new TypeReference<List<XpLogSourceView.RedactionRule>>() {});
        return new XpLogSourceView(e.getId(), e.getKind(), paths, e.getParser(), e.isEnabled(), red);
    }

    private static <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, type);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
