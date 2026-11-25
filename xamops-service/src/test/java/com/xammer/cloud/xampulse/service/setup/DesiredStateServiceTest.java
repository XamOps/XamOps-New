package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Disconnected, paused, revoked, out of scope and unknown all answer SKIP; only a covered instance answers INSTALL. */
class DesiredStateServiceTest {

    private static final String AWS = "123456789012";
    private static final String INST = "i-0123456789abcdef0";
    private final CloudAccountRepository accounts = mock(CloudAccountRepository.class);
    private final XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
    private final XpAccountRegionRepository regions = mock(XpAccountRegionRepository.class);
    private final XpAgentRepository agents = mock(XpAgentRepository.class);
    private final InstanceCandidateService candidates = mock(InstanceCandidateService.class);
    private DesiredStateService service;

    @BeforeEach
    void setUp() {
        service = new DesiredStateService(accounts, configs, regions, agents, candidates);
        CloudAccount a = new CloudAccount();
        a.setId(7L);
        a.setAwsAccountId(AWS);
        when(accounts.findByAwsAccountId(AWS)).thenReturn(List.of(a));
        when(agents.findByCloudAccountIdAndInstanceId(anyLong(), any())).thenReturn(Optional.empty());
        config("CONNECTED");
        region("INSTANCES", "[\"" + INST + "\"]");
    }

    private void config(String state) {
        XpAccountConfig c = new XpAccountConfig();
        c.setCloudAccountId(7L);
        c.setState(state);
        when(configs.findByCloudAccountId(7L)).thenReturn(Optional.of(c));
    }

    private void region(String mode, String idsJson) {
        XpAccountRegion r = new XpAccountRegion();
        r.setCloudAccountId(7L);
        r.setRegion("ap-south-1");
        r.setTargetMode(mode);
        r.setTargetInstanceIdsJson(idsJson);
        r.setObserveTagKey("xamops:observe");
        r.setObserveTagValue("true");
        when(regions.findByCloudAccountId(7L)).thenReturn(List.of(r));
    }

    private void agent(String state) {
        XpAgent a = new XpAgent();
        a.setCloudAccountId(7L);
        a.setInstanceId(INST);
        a.setRegion("ap-south-1");
        a.setState(state);
        when(agents.findByCloudAccountIdAndInstanceId(7L, INST)).thenReturn(Optional.of(a));
    }

    @Test
    void aSelectedInstanceOfAConnectedAccountGetsInstall() {
        assertEquals("INSTALL", service.answer(AWS, INST));
    }

    @Test
    void everyActiveStateGetsInstallButDisconnectedAndNotSetUpGetSkip() {
        for (String s : new String[] {"PLANNED", "DEPLOYING", "VERIFYING", "CONNECTED", "DEGRADED"}) {
            config(s);
            assertEquals("INSTALL", service.answer(AWS, INST), s);
        }
        for (String s : new String[] {"DISCONNECTED", "NOT_SET_UP", "garbage"}) {
            config(s);
            assertEquals("SKIP", service.answer(AWS, INST), s);
        }
    }

    @Test
    void pausedAndRevokedAgentsGetSkip() {
        agent("PAUSED");
        assertEquals("SKIP", service.answer(AWS, INST));
        agent("REVOKED");
        assertEquals("SKIP", service.answer(AWS, INST));
        agent("ACTIVE");
        assertEquals("INSTALL", service.answer(AWS, INST));
    }

    @Test
    void deSelectedAndUnknownInstancesGetSkip() {
        assertEquals("SKIP", service.answer(AWS, "i-0fffffffffffffff0"));
        assertEquals("SKIP", service.answer("999999999999", INST));
        when(configs.findByCloudAccountId(7L)).thenReturn(Optional.empty());
        assertEquals("SKIP", service.answer(AWS, INST));
    }

    @Test
    void malformedInputGetsSkipWithoutTouchingTheDatabase() {
        for (String[] bad : new String[][] {{null, INST}, {AWS, null}, {"abc", INST}, {AWS, "vol-1"}, {AWS + "0", INST}, {"", ""}}) {
            assertEquals("SKIP", service.answer(bad[0], bad[1]));
        }
    }

    @Test
    void tagTargetingNeedsTheInstanceToBeKnownToInventoryAndTagged() {
        region("TAG", null);
        when(candidates.inventoryLookup(7L, INST)).thenReturn(Optional.empty());
        assertEquals("SKIP", service.answer(AWS, INST));

        var tagged = new InstanceCandidateService.Seen(INST, "web", "ap-south-1", "running", Map.of("xamops:observe", "true"), null);
        when(candidates.inventoryLookup(7L, INST)).thenReturn(Optional.of(new InstanceCandidateService.Lookup(tagged, true)));
        assertEquals("INSTALL", service.answer(AWS, INST));

        var untagged = new InstanceCandidateService.Seen(INST, "web", "ap-south-1", "running", Map.of(), null);
        when(candidates.inventoryLookup(7L, INST)).thenReturn(Optional.of(new InstanceCandidateService.Lookup(untagged, true)));
        assertEquals("SKIP", service.answer(AWS, INST));
    }

    @Test
    void allManagedNeedsTheInstanceToBeSsmManaged() {
        region("ALL_MANAGED", null);
        var seen = new InstanceCandidateService.Seen(INST, "web", "ap-south-1", "running", Map.of(), null);
        when(candidates.inventoryLookup(eq(7L), eq(INST))).thenReturn(Optional.of(new InstanceCandidateService.Lookup(seen, false)));
        assertEquals("SKIP", service.answer(AWS, INST));
        when(candidates.inventoryLookup(eq(7L), eq(INST))).thenReturn(Optional.of(new InstanceCandidateService.Lookup(seen, true)));
        assertEquals("INSTALL", service.answer(AWS, INST));
    }

    @Test
    void whenTheSameAwsAccountIsConnectedTwiceAnyCoveringConnectionWins() {
        CloudAccount other = new CloudAccount();
        other.setId(8L);
        other.setAwsAccountId(AWS);
        CloudAccount mine = new CloudAccount();
        mine.setId(7L);
        when(accounts.findByAwsAccountId(AWS)).thenReturn(List.of(other, mine));
        when(configs.findByCloudAccountId(8L)).thenReturn(Optional.empty());
        assertEquals("INSTALL", service.answer(AWS, INST));
    }
}
