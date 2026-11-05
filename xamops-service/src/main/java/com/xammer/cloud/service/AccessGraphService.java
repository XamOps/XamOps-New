package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.dto.dbops.AccessGraphDoc;
import com.xammer.cloud.dto.dbops.AccessGraphDoc.AgEdge;
import com.xammer.cloud.dto.dbops.AccessGraphDoc.AgInsight;
import com.xammer.cloud.dto.dbops.AccessGraphDoc.AgMeta;
import com.xammer.cloud.dto.dbops.AccessGraphDoc.AgNode;
import com.xammer.cloud.dto.dbops.AccessGraphDoc.AgNodeData;
import com.xammer.cloud.dto.dbops.AgentAccessGrantDto;
import com.xammer.cloud.dto.dbops.AgentAccessGraphDoc;
import com.xammer.cloud.dto.dbops.AgentAccessRoleDto;
import com.xammer.cloud.dto.dbops.AgentMembershipDto;
import com.xammer.cloud.dto.dbops.AgentUserActivityDto;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pillar B — derives the cytoscape access graph ({@link AccessGraphDoc}) and governance insights
 * from the agent's raw {@link AgentAccessGraphDoc} (§7.2). Server-side it expands role membership
 * transitively (effective access), flags over-privileged / unused / PUBLIC grants, and bounds the
 * principal rank to {@code ?topN} so a many-role estate cannot return an unbounded document
 * (rev-3 Finding 12). {@link #build} is pure for unit testing; {@link #buildForAccount} reads cache.
 */
@Service
public class AccessGraphService {

    private static final Logger logger = LoggerFactory.getLogger(AccessGraphService.class);

    static final int DEFAULT_TOPN = 300;
    static final int MIN_TOPN = 1;
    static final int MAX_TOPN = 2000;
    static final long OVER_PRIVILEGED_OBJECT_FLOOR = 100; // broad WRITE/DDL over this many objects

    private static final List<String> CLASS_ORDER =
            List.of("READ", "WRITE", "DDL", "USAGE_EXEC", "CONNECT", "ADMIN");

    private final DbAgentConfigRepository dbAgentConfigRepository;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final ObjectMapper objectMapper;

    public AccessGraphService(DbAgentConfigRepository dbAgentConfigRepository,
                              DbAgentDataCacheRepository dbAgentDataCacheRepository) {
        this.dbAgentConfigRepository = dbAgentConfigRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public static int clampTopN(Integer topN) {
        if (topN == null) return DEFAULT_TOPN;
        return Math.max(MIN_TOPN, Math.min(MAX_TOPN, topN));
    }

    // ───────────────────────────── cache-reading entry point ─────────────────────────────

    public AccessGraphDoc buildForAccount(CloudAccount account, String dsnAlias, Integer topN) {
        List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(account.getId());

        // Gather the per-DSN access-grants docs across agents, plus the user-activity feed.
        List<AgentAccessGraphDoc> docs = new ArrayList<>();
        List<AgentUserActivityDto> userActivity = new ArrayList<>();
        for (DbAgentConfig agent : agents) {
            for (DbAgentDataCache c : dbAgentDataCacheRepository.findByDbAgentId(agent.getId())) {
                if (c.getDataType() == null || c.getDataJson() == null) continue;
                if (c.getDataType().startsWith("db-access-grants:")) {
                    AgentAccessGraphDoc doc = parseDoc(c.getDataJson());
                    if (doc != null) docs.add(doc);
                }
            }
            Optional<DbAgentDataCache> ua = dbAgentDataCacheRepository
                    .findByDbAgentIdAndDataType(agent.getId(), "db-user-activity");
            ua.ifPresent(c -> userActivity.addAll(parseList(c.getDataJson(),
                    new TypeReference<List<AgentUserActivityDto>>() {})));
        }

        if (docs.isEmpty()) {
            return AccessGraphDoc.empty();
        }

        TreeSet<String> aliasSet = new TreeSet<>();
        for (AgentAccessGraphDoc d : docs) {
            if (d.getDsnAlias() != null) aliasSet.add(d.getDsnAlias());
        }
        List<String> availableDsnAliases = new ArrayList<>(aliasSet);
        String selected = (dsnAlias != null && aliasSet.contains(dsnAlias))
                ? dsnAlias : availableDsnAliases.get(0);

        AgentAccessGraphDoc doc = docs.stream()
                .filter(d -> selected.equals(d.getDsnAlias())).findFirst().orElse(null);
        if (doc == null) return AccessGraphDoc.empty();

        boolean stale = DbHealthConsoleService.isStale(doc.getCollectedAt(),
                DbHealthConsoleService.DEFAULT_COLLECTOR_INTERVAL_SECS);
        return build(doc, userActivity, clampTopN(topN), stale, availableDsnAliases);
    }

    // ───────────────────────────── pure derivation ─────────────────────────────

    public AccessGraphDoc build(AgentAccessGraphDoc doc, List<AgentUserActivityDto> userActivity,
                                int topN, boolean stale, List<String> availableDsnAliases) {
        List<AgentAccessRoleDto> roles = doc.getRoles() != null ? doc.getRoles() : List.of();
        List<AgentMembershipDto> memberships = doc.getMemberships() != null ? doc.getMemberships() : List.of();
        List<AgentAccessGrantDto> grants = doc.getGrants() != null ? doc.getGrants() : List.of();

        Map<String, AgentAccessRoleDto> roleByName = new HashMap<>();
        for (AgentAccessRoleDto r : roles) roleByName.put(r.getName(), r);

        // member → set of directly-granted roles (for transitive expansion).
        Map<String, Set<String>> grantedTo = new HashMap<>();
        for (AgentMembershipDto m : memberships) {
            grantedTo.computeIfAbsent(m.getMember(), k -> new LinkedHashSet<>()).add(m.getGrantedRole());
        }

        // grantee → direct privilege classes (object grants) + object count per class.
        Map<String, Set<String>> directClasses = new HashMap<>();
        Map<String, Long> objectCountByGrantee = new HashMap<>();
        Map<String, Long> objectCountByClass = new HashMap<>();
        Map<String, Boolean> hasPublicByClass = new HashMap<>();
        Set<String> classesPresent = new LinkedHashSet<>();
        for (AgentAccessGrantDto g : grants) {
            String cls = g.getPrivilegeClass();
            if (cls == null) continue;
            classesPresent.add(cls);
            objectCountByClass.merge(cls, (long) g.getObjectCount(), Long::sum);
            if (g.isHasPublic()) hasPublicByClass.put(cls, true);
            if (!"PUBLIC".equals(g.getGrantee())) {
                directClasses.computeIfAbsent(g.getGrantee(), k -> new LinkedHashSet<>()).add(cls);
                objectCountByGrantee.merge(g.getGrantee(), (long) g.getObjectCount(), Long::sum);
            }
        }
        // Role attributes contribute an ADMIN class (ownership / cluster rights).
        for (AgentAccessRoleDto r : roles) {
            if (r.isSuperuser() || r.isCreateRole()) {
                directClasses.computeIfAbsent(r.getName(), k -> new LinkedHashSet<>()).add("ADMIN");
                classesPresent.add("ADMIN");
            }
        }

        // Partition roles into login principals and non-login group roles (that participate).
        List<AgentAccessRoleDto> principals = new ArrayList<>();
        Set<String> groupRoleNames = new LinkedHashSet<>();
        for (AgentAccessRoleDto r : roles) {
            if (r.isCanLogin()) principals.add(r);
            else groupRoleNames.add(r.getName());
        }
        // A grantee or granted-role that isn't a known login role is treated as a group role node.
        for (String grantee : directClasses.keySet()) {
            if (!roleByName.containsKey(grantee) || !roleByName.get(grantee).isCanLogin()) {
                groupRoleNames.add(grantee);
            }
        }
        for (AgentMembershipDto m : memberships) {
            if (!roleByName.containsKey(m.getGrantedRole()) || !roleByName.get(m.getGrantedRole()).isCanLogin()) {
                groupRoleNames.add(m.getGrantedRole());
            }
        }

        // Effective access per principal (transitive membership) → breadth for topN ranking.
        Map<String, Set<String>> effectiveClasses = new HashMap<>();
        Map<String, List<String>> inheritedFrom = new HashMap<>();
        for (AgentAccessRoleDto p : principals) {
            Set<String> reached = new LinkedHashSet<>();
            collectRoles(p.getName(), grantedTo, reached, new HashSet<>());
            Set<String> classes = new LinkedHashSet<>(directClasses.getOrDefault(p.getName(), Set.of()));
            List<String> inh = new ArrayList<>();
            for (String role : reached) {
                if (role.equals(p.getName())) continue;
                inh.add(role);
                classes.addAll(directClasses.getOrDefault(role, Set.of()));
            }
            effectiveClasses.put(p.getName(), classes);
            inheritedFrom.put(p.getName(), inh);
        }

        // topN principal bound, ranked by effective-privilege breadth then object count.
        principals.sort(Comparator
                .comparingInt((AgentAccessRoleDto p) -> effectiveClasses.getOrDefault(p.getName(), Set.of()).size())
                .thenComparingLong(p -> objectCountByGrantee.getOrDefault(p.getName(), 0L))
                .reversed()
                .thenComparing(AgentAccessRoleDto::getName));
        int totalPrincipals = principals.size();
        int keep = Math.min(topN, totalPrincipals);
        List<AgentAccessRoleDto> keptPrincipals = principals.subList(0, keep);
        Set<String> keptPrincipalNames = new HashSet<>();
        for (AgentAccessRoleDto p : keptPrincipals) keptPrincipalNames.add(p.getName());
        int hiddenPrincipalCount = totalPrincipals - keep;

        // ── Build user-activity lookup for the unused-grant signal ──
        Map<String, AgentUserActivityDto> activityByUser = new HashMap<>();
        if (userActivity != null) {
            for (AgentUserActivityDto ua : userActivity) {
                if (ua.getUsername() != null) activityByUser.put(ua.getUsername(), ua);
            }
        }

        // ── Nodes ──
        List<AgNode> nodes = new ArrayList<>();
        String dsnAlias = doc.getDsnAlias();
        String dsId = "ds:" + dsnAlias;
        nodes.add(new AgNode(dsId, "datastore", doc.getDbHost() != null && !doc.getDbHost().isEmpty() ? doc.getDbHost() : dsnAlias,
                new AgNodeData(doc.getDbHost(), groupRoleNames.size() + keptPrincipalNames.size(), keptPrincipalNames.size(),
                        null, null, null, null, null, null, null, null, null, null, null, null, null),
                null));

        List<String> orderedClasses = new ArrayList<>();
        for (String c : CLASS_ORDER) if (classesPresent.contains(c)) orderedClasses.add(c);
        for (String cls : orderedClasses) {
            nodes.add(new AgNode("priv:" + cls, "privilege", privilegeLabel(cls),
                    new AgNodeData(null, null, null, cls,
                            objectCountByClass.getOrDefault(cls, 0L).intValue(),
                            hasPublicByClass.getOrDefault(cls, false),
                            null, null, null, null, null, null, null, null, null, null),
                    hasPublicByClass.getOrDefault(cls, false) ? "warning" : null));
        }
        for (String g : groupRoleNames) {
            List<String> classes = new ArrayList<>(directClasses.getOrDefault(g, Set.of()));
            nodes.add(new AgNode("role:" + g, "role", g,
                    new AgNodeData(null, null, null, null, null, null,
                            true, new ArrayList<>(grantedTo.getOrDefault(g, Set.of())), classes,
                            null, null, null, null, null, null, null),
                    null));
        }
        for (AgentAccessRoleDto p : keptPrincipals) {
            List<String> flags = new ArrayList<>();
            if (p.isSuperuser()) flags.add("superuser");
            if (p.isBypassRls()) flags.add("bypassRls");
            String severity = p.isSuperuser() ? "critical" : (p.isBypassRls() ? "warning" : null);
            nodes.add(new AgNode("principal:" + p.getName(), "principal", p.getName(),
                    new AgNodeData(null, null, null, null, null, null,
                            null, null, null,
                            p.isCanLogin(), p.isSuperuser(), p.isBypassRls(), p.getConnLimit(),
                            inheritedFrom.getOrDefault(p.getName(), List.of()),
                            new ArrayList<>(effectiveClasses.getOrDefault(p.getName(), Set.of())),
                            flags),
                    severity));
        }

        Set<String> nodeIds = new HashSet<>();
        for (AgNode n : nodes) nodeIds.add(n.id());

        // ── Edges (edges[] ⊆ nodes[]) ──
        List<AgEdge> edges = new ArrayList<>();
        for (String cls : orderedClasses) {
            edges.add(new AgEdge(dsId + "->priv:" + cls, dsId, "priv:" + cls, "datastore-class"));
        }
        // holds-class: privilege → grantee (role or principal).
        Set<String> holdsSeen = new HashSet<>();
        for (AgentAccessGrantDto g : grants) {
            if ("PUBLIC".equals(g.getGrantee()) || g.getPrivilegeClass() == null) continue;
            addHoldsEdge(edges, nodeIds, holdsSeen, g.getPrivilegeClass(), g.getGrantee());
        }
        for (AgentAccessRoleDto r : roles) {
            if (r.isSuperuser() || r.isCreateRole()) {
                addHoldsEdge(edges, nodeIds, holdsSeen, "ADMIN", r.getName());
            }
        }
        // membership: granted (group) role → member (principal or role).
        for (AgentMembershipDto m : memberships) {
            String src = nodeIdFor(m.getGrantedRole(), roleByName);
            String tgt = nodeIdFor(m.getMember(), roleByName);
            if (src == null || tgt == null) continue;
            if (nodeIds.contains(src) && nodeIds.contains(tgt)) {
                edges.add(new AgEdge(src + "->" + tgt, src, tgt, "membership"));
            }
        }

        // ── Insights ──
        List<AgInsight> insights = new ArrayList<>();
        for (AgentAccessRoleDto p : principals) { // insights consider all principals, not only kept
            if (p.isSuperuser()) {
                insights.add(new AgInsight("over-privileged:" + p.getName(), "critical", p.getName(),
                        "Superuser account", "Role " + p.getName()
                        + " is a superuser (bypasses all permission checks). Least-privilege: grant only what is needed."));
            } else if (p.isBypassRls()) {
                insights.add(new AgInsight("over-privileged:" + p.getName(), "warning", p.getName(),
                        "Bypasses row-level security", "Role " + p.getName() + " has rolbypassrls."));
            } else if (objectCountByGrantee.getOrDefault(p.getName(), 0L) >= OVER_PRIVILEGED_OBJECT_FLOOR
                    && (effectiveClasses.getOrDefault(p.getName(), Set.of()).contains("WRITE")
                        || effectiveClasses.getOrDefault(p.getName(), Set.of()).contains("DDL"))) {
                insights.add(new AgInsight("over-privileged:" + p.getName(), "warning", p.getName(),
                        "Broad write/DDL access",
                        "Role " + p.getName() + " holds WRITE/DDL over "
                                + objectCountByGrantee.get(p.getName()) + " objects."));
            }
            // Unused-grant signal (conservative, with the cumulative-since-reset caveat).
            AgentUserActivityDto ua = activityByUser.get(p.getName());
            boolean hasGrants = !effectiveClasses.getOrDefault(p.getName(), Set.of()).isEmpty();
            if (hasGrants && ua != null && ua.getCallCount() == 0
                    && ua.getActiveConnections() == 0 && ua.getIdleConnections() == 0) {
                insights.add(new AgInsight("unused:" + p.getName(), "info", p.getName(),
                        "Granted, not observed in use",
                        "As of " + orEmpty(ua.getCollectedAt())
                                + "; based on cumulative call counts since the last stats reset — a recent reset, "
                                + "or a role simply idle at snapshot time, can read as unused."));
            }
        }
        for (String cls : orderedClasses) {
            if (hasPublicByClass.getOrDefault(cls, false)) {
                insights.add(new AgInsight("public-grant:" + cls, "warning", cls,
                        "PUBLIC grant", "Privilege class " + cls + " is granted to PUBLIC (every role)."));
            }
        }

        AgMeta meta = new AgMeta(hiddenPrincipalCount, hiddenPrincipalCount > 0, totalPrincipals);
        return new AccessGraphDoc(dsnAlias, doc.getCollectedAt(), stale, nodes, edges, insights, meta,
                availableDsnAliases != null ? availableDsnAliases : List.of());
    }

    // ── helpers ──

    private static void addHoldsEdge(List<AgEdge> edges, Set<String> nodeIds, Set<String> seen,
                                     String cls, String grantee) {
        String priv = "priv:" + cls;
        String target = "role:" + grantee;
        if (!nodeIds.contains(target)) target = "principal:" + grantee;
        if (!nodeIds.contains(priv) || !nodeIds.contains(target)) return;
        String id = priv + "->" + target;
        if (seen.add(id)) {
            edges.add(new AgEdge(id, priv, target, "holds-class"));
        }
    }

    private static String nodeIdFor(String roleName, Map<String, AgentAccessRoleDto> roleByName) {
        if (roleName == null) return null;
        AgentAccessRoleDto r = roleByName.get(roleName);
        if (r != null && r.isCanLogin()) return "principal:" + roleName;
        return "role:" + roleName;
    }

    private static void collectRoles(String role, Map<String, Set<String>> grantedTo,
                                     Set<String> acc, Set<String> visiting) {
        if (!visiting.add(role)) return; // cycle guard
        acc.add(role);
        for (String g : grantedTo.getOrDefault(role, Set.of())) {
            collectRoles(g, grantedTo, acc, visiting);
        }
        visiting.remove(role);
    }

    private static String privilegeLabel(String cls) {
        return switch (cls) {
            case "USAGE_EXEC" -> "Usage/Exec";
            case "READ" -> "Read";
            case "WRITE" -> "Write";
            case "DDL" -> "DDL";
            case "CONNECT" -> "Connect";
            case "ADMIN" -> "Admin";
            default -> cls;
        };
    }

    private AgentAccessGraphDoc parseDoc(String json) {
        try {
            return objectMapper.readValue(json, AgentAccessGraphDoc.class);
        } catch (Exception e) {
            logger.warn("[AccessGraph] parse failed: {}", e.getMessage());
            return null;
        }
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<T> items = objectMapper.readValue(json, typeRef);
            return items != null ? items : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
