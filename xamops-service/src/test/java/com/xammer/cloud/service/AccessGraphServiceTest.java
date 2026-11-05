package com.xammer.cloud.service;

import com.xammer.cloud.dto.dbops.AccessGraphDoc;
import com.xammer.cloud.dto.dbops.AccessGraphDoc.AgNode;
import com.xammer.cloud.dto.dbops.AgentAccessGrantDto;
import com.xammer.cloud.dto.dbops.AgentAccessGraphDoc;
import com.xammer.cloud.dto.dbops.AgentAccessRoleDto;
import com.xammer.cloud.dto.dbops.AgentMembershipDto;
import com.xammer.cloud.dto.dbops.AgentUserActivityDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Pillar B derivation (§7.2): effective-access expansion, governance insights, and the topN cap. */
class AccessGraphServiceTest {

    private final AccessGraphService service = new AccessGraphService(
            mock(com.xammer.cloud.repository.DbAgentConfigRepository.class),
            mock(com.xammer.cloud.repository.DbAgentDataCacheRepository.class));

    private AgentAccessRoleDto role(String name, boolean login, boolean superuser) {
        AgentAccessRoleDto r = new AgentAccessRoleDto();
        r.setName(name);
        r.setCanLogin(login);
        r.setSuperuser(superuser);
        return r;
    }

    private AgentAccessGrantDto grant(String grantee, String scope, String cls, int objs, boolean pub) {
        AgentAccessGrantDto g = new AgentAccessGrantDto();
        g.setGrantee(grantee);
        g.setScope(scope);
        g.setPrivilegeClass(cls);
        g.setObjectCount(objs);
        g.setHasPublic(pub);
        return g;
    }

    private AgentMembershipDto membership(String member, String grantedRole) {
        AgentMembershipDto m = new AgentMembershipDto();
        m.setMember(member);
        m.setGrantedRole(grantedRole);
        return m;
    }

    private AgentAccessGraphDoc fixture() {
        AgentAccessGraphDoc doc = new AgentAccessGraphDoc();
        doc.setDsnAlias("db-0");
        doc.setDbHost("pg.internal");
        doc.setCollectedAt("2026-10-29T12:00:00Z");
        doc.setRoles(List.of(
                role("app_read", false, false),   // group role
                role("alice", true, false),        // member of app_read
                role("root", true, true),          // superuser → over-privileged
                role("bob", true, false)));         // unused (no activity)
        doc.setMemberships(List.of(membership("alice", "app_read")));
        doc.setGrants(List.of(
                grant("app_read", "public", "READ", 5, false),
                grant("bob", "public", "WRITE", 3, false),
                grant("PUBLIC", "mydb", "CONNECT", 1, true)));
        return doc;
    }

    private AgentUserActivityDto unusedBob() {
        AgentUserActivityDto ua = new AgentUserActivityDto();
        ua.setUsername("bob");
        ua.setCallCount(0);
        ua.setActiveConnections(0);
        ua.setIdleConnections(0);
        ua.setCollectedAt("2026-10-29T12:00:00Z");
        return ua;
    }

    @Test
    void expandsEffectiveAccessThroughMembership() {
        AccessGraphDoc out = service.build(fixture(), List.of(unusedBob()), 300, false, List.of("db-0"));

        AgNode alice = out.nodes().stream()
                .filter(n -> "principal".equals(n.kind()) && "alice".equals(n.label()))
                .findFirst().orElseThrow();
        assertThat(alice.data().effectivePrivilegeClasses()).contains("READ"); // inherited from app_read
        assertThat(alice.data().inheritedFrom()).contains("app_read");

        // Membership edge group → principal, and a holds-class edge priv:READ → role:app_read.
        assertThat(out.edges()).anyMatch(e -> "membership".equals(e.kind())
                && e.source().equals("role:app_read") && e.target().equals("principal:alice"));
        assertThat(out.edges()).anyMatch(e -> "holds-class".equals(e.kind())
                && e.source().equals("priv:READ") && e.target().equals("role:app_read"));
        // Every edge endpoint is a real node (invariant).
        var ids = out.nodes().stream().map(AgNode::id).toList();
        assertThat(out.edges()).allSatisfy(e -> {
            assertThat(ids).contains(e.source());
            assertThat(ids).contains(e.target());
        });
    }

    @Test
    void emitsOverPrivilegedUnusedAndPublicInsights() {
        AccessGraphDoc out = service.build(fixture(), List.of(unusedBob()), 300, false, List.of("db-0"));

        assertThat(out.insights()).anyMatch(i -> i.id().equals("over-privileged:root") && "critical".equals(i.severity()));
        assertThat(out.insights()).anyMatch(i -> i.id().equals("unused:bob"));
        assertThat(out.insights()).anyMatch(i -> i.id().equals("public-grant:CONNECT") && "warning".equals(i.severity()));

        // root's superuser attribute surfaces an ADMIN privilege bucket.
        assertThat(out.nodes()).anyMatch(n -> "privilege".equals(n.kind()) && "priv:ADMIN".equals(n.id()));
    }

    @Test
    void topNCapsPrincipalsAndReportsHidden() {
        AccessGraphDoc out = service.build(fixture(), List.of(), 1, false, List.of("db-0"));

        long principalNodes = out.nodes().stream().filter(n -> "principal".equals(n.kind())).count();
        assertThat(principalNodes).isEqualTo(1);
        assertThat(out.meta().totalPrincipals()).isEqualTo(3);
        assertThat(out.meta().hiddenPrincipalCount()).isEqualTo(2);
        assertThat(out.meta().truncated()).isTrue();
    }

    @Test
    void emptyDocIsHonestEmpty() {
        AccessGraphDoc out = AccessGraphDoc.empty();
        assertThat(out.nodes()).isEmpty();
        assertThat(out.edges()).isEmpty();
        assertThat(out.meta().totalPrincipals()).isZero();
    }
}
