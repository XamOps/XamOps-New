package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.domain.XpAgentCredential;
import com.xammer.cloud.xampulse.dto.XpDisconnectRequest;
import com.xammer.cloud.xampulse.dto.XpDisconnectResponse;
import com.xammer.cloud.xampulse.dto.XpTrustPanel;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.repository.XpAgentCredentialRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TrustPanelAndDisconnectTest {

    // ---------------------------------------------------------------- trust panel

    private final TrustPanelService trust = new TrustPanelService(new XamPulseProperties());

    @Test
    void panelCarriesTheRequiredConnectionRoleSentence() {
        XpTrustPanel p = trust.panel(null, "abc");
        assertTrue(p.permissionsDiff().connectionRoleNote().contains("account-wide S3 read through AWS ReadOnlyAccess"));
        assertTrue(p.permissionsDiff().connectionRoleNote().contains("In CREATE mode the bucket policy blocks it from the archive prefix."));
        assertTrue(p.permissionsDiff().connectionRoleNote().contains("In EXISTING mode you can apply the deny snippet shown in the preview."));
        assertTrue(p.access().contains(TrustPanelService.CONNECTION_ROLE_NOTE));
        assertEquals("abc", p.templateSha256());
        assertTrue(p.permissionsDiff().noRunCommand().contains("cannot run commands"));
        assertTrue(p.neverCollected().contains("Process command lines"));
        assertTrue(p.permissionsDiff().archiveRole().get(0).contains("cannot delete objects"));
    }

    @Test
    void grantsAreParsedFromTheShippedConnectionRoleTemplate() {
        List<String> grants = trust.connectionRoleGrants();
        assertFalse(grants.isEmpty());
        // The write grants the connection role really has, read from cloudformation/xamops-readonly-role.yml.
        assertTrue(grants.stream().anyMatch(g -> g.contains("s3:PutObject") && g.contains("s3:DeleteObject") && g.contains("xamops-mediaops/")), grants.toString());
        assertTrue(grants.stream().anyMatch(g -> g.contains("cloudformation:CreateStack") && g.contains("XamOps-CUR-")), grants.toString());
        assertTrue(grants.stream().anyMatch(g -> g.contains("s3:CreateBucket") && g.contains("xamops-cur-")), grants.toString());
        assertTrue(grants.stream().noneMatch(g -> g.contains("xamops-xampulse")), "no grant may touch the archive prefix");
        // Read-only statements are not listed.
        assertTrue(grants.stream().noneMatch(g -> g.startsWith("ec2:Describe")));
    }

    @Test
    void parserListsOnlyAllowedWriteActionsOfTheConnectionRole() {
        String yaml = """
                Resources:
                  XamOpsReadOnlyRole:
                    Type: AWS::IAM::Role
                    Properties:
                      Policies:
                        - PolicyName: p
                          PolicyDocument:
                            Statement:
                              - Effect: Allow
                                Action: ['ec2:Describe*', 's3:PutObject']
                                Resource: !Sub 'arn:aws:s3:::b-${AWS::AccountId}/x/*'
                              - Effect: Deny
                                Action: ['s3:DeleteObject']
                                Resource: '*'
                              - Effect: Allow
                                Action: ['s3:GetObject', 'logs:StartQuery']
                                Resource: '*'
                  OtherRole:
                    Type: AWS::IAM::Role
                    Properties:
                      Policies:
                        - PolicyName: q
                          PolicyDocument:
                            Statement:
                              - Effect: Allow
                                Action: ['iam:*']
                                Resource: '*'
                """;
        assertEquals(List.of("s3:PutObject on arn:aws:s3:::b-<your account>/x/*", "logs:StartQuery on *"), TrustPanelService.parseGrants(yaml));
    }

    @Test
    void whereBlockShowsTheCustomersBucketAndHotRetention() {
        XpAccountConfig c = new XpAccountConfig();
        c.setBucketName("my-logs");
        c.setBucketRegion("ap-south-1");
        c.setHotDays(21);
        c.setKmsKeyArn("arn:aws:kms:ap-south-1:123456789012:key/abc");
        XpTrustPanel p = trust.panel(c, "sha");
        assertEquals("my-logs", p.where().archiveBucket());
        assertEquals("ap-south-1", p.where().archiveRegion());
        assertEquals(21, p.where().hotDays());
        assertEquals("arn:aws:kms:ap-south-1:123456789012:key/abc", p.where().kms());
    }

    // ---------------------------------------------------------------- disconnect

    private final XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
    private final XpAccountRegionRepository regions = mock(XpAccountRegionRepository.class);
    private final XpAgentRepository agents = mock(XpAgentRepository.class);
    private final XpAgentCredentialRepository credentials = mock(XpAgentCredentialRepository.class);
    private final com.xammer.cloud.xampulse.service.archive.TenantPurgeService purgeService = mock(com.xammer.cloud.xampulse.service.archive.TenantPurgeService.class);
    private final com.xammer.cloud.xampulse.config.XamPulseProperties disconnectProps = new com.xammer.cloud.xampulse.config.XamPulseProperties();
    private final DisconnectService disconnect = new DisconnectService(configs, regions, agents, credentials, mock(AuditService.class), purgeService, disconnectProps);

    private CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(7L);
        return a;
    }

    private XpAccountConfig config(String state) {
        XpAccountConfig c = new XpAccountConfig();
        c.setCloudAccountId(7L);
        c.setState(state);
        when(configs.findByCloudAccountId(7L)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void disconnectNeedsTheTypedConfirmation() {
        config("CONNECTED");
        for (String bad : new String[] {null, "", "disconnect", "yes"}) {
            assertThrows(XamPulseException.class, () -> disconnect.disconnect(account(), new XpDisconnectRequest(false, bad), null, "1.1.1.1"));
        }
        assertThrows(XamPulseException.class, () -> disconnect.disconnect(account(), null, null, "1.1.1.1"));
        verify(agents, never()).save(any());
    }

    @Test
    void hotDataPurgeIsRefusedInsteadOfPretended() {
        config("CONNECTED");
        XamPulseException e = assertThrows(XamPulseException.class,
                () -> disconnect.disconnect(account(), new XpDisconnectRequest(true, "DISCONNECT"), null, "1.1.1.1"));
        assertEquals("XP_INVALID_REQUEST", e.getCode());
        assertEquals("CONNECTED", configs.findByCloudAccountId(7L).get().getState());
    }

    @Test
    void hotDataPurgeRunsOnlyWhenTheArchiveFeatureIsOnAndNeverTouchesOtherTenants() {
        disconnectProps.getArchive().setEnabled(true);
        config("CONNECTED");
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of());
        when(regions.findByCloudAccountId(7L)).thenReturn(List.of());
        disconnect.disconnect(account(), new XpDisconnectRequest(true, "DISCONNECT"), null, "1.1.1.1");
        verify(purgeService).purge(7L);
        verifyNoMoreInteractions(purgeService);
    }

    @Test
    void disconnectWithoutPurgeNeverCallsPurge() {
        disconnectProps.getArchive().setEnabled(true);
        config("CONNECTED");
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of());
        when(regions.findByCloudAccountId(7L)).thenReturn(List.of());
        disconnect.disconnect(account(), new XpDisconnectRequest(false, "DISCONNECT"), null, "1.1.1.1");
        verifyNoInteractions(purgeService);
    }

    @Test
    void disconnectRevokesAgentsAndCredentialsAndReturnsStackLinksPerRegion() {
        XpAccountConfig c = config("CONNECTED");
        XpAgent a = new XpAgent();
        a.setId(1L);
        a.setCloudAccountId(7L);
        a.setState("ACTIVE");
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of(a));
        XpAgentCredential cred = new XpAgentCredential();
        cred.setAgentId(1L);
        when(credentials.findByAgentId(1L)).thenReturn(List.of(cred));
        XpAccountRegion r1 = new XpAccountRegion();
        r1.setRegion("ap-south-1");
        r1.setStackName("XamOpsXamPulse-123456789012");
        XpAccountRegion r2 = new XpAccountRegion();
        r2.setRegion("eu-west-1");
        r2.setStackName("XamOpsXamPulseRegion-123456789012-eu-west-1");
        when(regions.findByCloudAccountId(7L)).thenReturn(List.of(r1, r2));

        XpDisconnectResponse res = disconnect.disconnect(account(), new XpDisconnectRequest(false, "DISCONNECT"), null, "1.1.1.1");

        assertEquals("REVOKED", a.getState());
        assertEquals("UNINSTALL", a.getPendingCommand());
        assertNotNull(cred.getRevokedAt());
        assertEquals("DISCONNECTED", c.getState());
        assertEquals(2, res.stackDeletionLinks().size());
        assertTrue(res.stackDeletionLinks().get(1).url().contains("eu-west-1.console.aws.amazon.com"));
        assertTrue(res.stackDeletionLinks().get(1).url().contains("XamOpsXamPulseRegion-123456789012-eu-west-1"));
    }

    @Test
    void disconnectIsIdempotent() {
        config("DISCONNECTED");
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of());
        when(regions.findByCloudAccountId(7L)).thenReturn(List.of());
        XpDisconnectResponse res = disconnect.disconnect(account(), new XpDisconnectRequest(false, "DISCONNECT"), null, "1.1.1.1");
        assertNotNull(res.jobId());
        assertEquals("DISCONNECTED", configs.findByCloudAccountId(7L).get().getState());
    }

    @Test
    void disconnectOfAnAccountThatWasNeverSetUpIsRefused() {
        when(configs.findByCloudAccountId(7L)).thenReturn(Optional.empty());
        assertThrows(XamPulseException.class, () -> disconnect.disconnect(account(), new XpDisconnectRequest(false, "DISCONNECT"), null, "1.1.1.1"));
    }
}
