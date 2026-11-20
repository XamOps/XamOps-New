package com.xammer.cloud.service;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.DeleteAutoScalingGroupRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
/** FR-CMP-02, design-core.md section 7.6: the waste delete of an Auto Scaling Group AutoSpotting manages. */
class WasteDeleteManagedGroupTest {
    private final AwsClientProvider aws = mock(AwsClientProvider.class);
    private final CloudAccountRepository accounts = mock(CloudAccountRepository.class);
    private final DashboardDataService dashboard = mock(DashboardDataService.class);
    private final SpotGroupRepository spotGroups = mock(SpotGroupRepository.class);
    private final AutoSpottingAuditWriter audit = mock(AutoSpottingAuditWriter.class);
    private final AutoScalingClient asgClient = mock(AutoScalingClient.class);
    private WasteDeleteService service;
    @BeforeEach
    void setUp() {
        CloudAccount account = new CloudAccount();
        account.setId(5L);
        account.setAccessType("read-write");
        when(accounts.findByAwsAccountId("111122223333")).thenReturn(List.of(account));
        when(aws.getAutoScalingClient(any(), anyString())).thenReturn(asgClient);
        service = new WasteDeleteService(aws, accounts, dashboard, spotGroups, audit,
                mock(PlatformTransactionManager.class));
    }
    private void groupInState(GroupState state) {
        SpotGroup g = new SpotGroup();
        g.setId(UUID.randomUUID());
        g.setClientId(1L);
        g.setCloudAccountId(5L);
        g.setGroupKey("us-east-1/web");
        g.setState(state);
        when(spotGroups.findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(eq(Provider.AWS), eq(5L),
                eq("us-east-1/web"), eq(GroupState.GONE))).thenReturn(Optional.of(g));
    }
    private void delete(boolean acknowledged) {
        service.deleteResource("111122223333", "web", "Auto Scaling Group", "us-east-1", acknowledged, null);
    }
    @Test
    void managedGroupWithoutAcknowledgmentIs409AndMakesNoAwsCall() {
        groupInState(GroupState.ACTIVE);
        assertThatThrownBy(() -> delete(false)).isInstanceOfSatisfying(WasteDeleteBlockedException.class, e -> {
            assertThat(e.getCode()).isEqualTo("AUTOSPOTTING_MANAGED_GROUP");
            assertThat(e.getState()).isEqualTo("ACTIVE");
            assertThat(e.getStatus().value()).isEqualTo(409);
        });
        verifyNoInteractions(aws);
        verifyNoInteractions(audit);
    }
    @Test
    void acknowledgedDeleteWritesTheAuditRowThenCallsDeleteOnce() {
        groupInState(GroupState.FALLBACK);
        delete(true);
        ArgumentCaptor<AuditRecord> rec = ArgumentCaptor.forClass(AuditRecord.class);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(audit, asgClient);
        order.verify(audit).write(rec.capture());
        order.verify(asgClient).deleteAutoScalingGroup(any(DeleteAutoScalingGroupRequest.class));
        assertThat(rec.getValue().eventType()).isEqualTo("WASTE_DELETE_ACKNOWLEDGED");
        assertThat(rec.getValue().beforeState()).contains("FALLBACK");
        verify(asgClient, org.mockito.Mockito.times(1)).deleteAutoScalingGroup(any(DeleteAutoScalingGroupRequest.class));
    }
    @Test
    void applyingAndRevertingAreRefusedEvenWhenAcknowledged() {
        for (GroupState s : List.of(GroupState.APPLYING, GroupState.REVERTING)) {
            groupInState(s);
            assertThatThrownBy(() -> delete(true)).isInstanceOfSatisfying(WasteDeleteBlockedException.class,
                    e -> assertThat(e.getCode()).isEqualTo("AS_GROUP_BUSY"));
        }
        verifyNoInteractions(aws);
        verifyNoInteractions(audit);
    }
    @Test
    void groupWithoutRowBehavesAsToday() {
        when(spotGroups.findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(any(), anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        delete(false);
        verify(asgClient).deleteAutoScalingGroup(any(DeleteAutoScalingGroupRequest.class));
        verifyNoInteractions(audit);
    }
    @Test
    void unmanagedAndRevertedGroupsBehaveAsToday() {
        for (GroupState s : List.of(GroupState.UNMANAGED, GroupState.REVERTED)) {
            groupInState(s);
            delete(false);
        }
        verify(asgClient, org.mockito.Mockito.times(2)).deleteAutoScalingGroup(any(DeleteAutoScalingGroupRequest.class));
        verifyNoInteractions(audit);
    }
    @Test
    void otherResourceTypesAreNotChecked() {
        when(aws.getEc2Client(any(), anyString())).thenReturn(mock(software.amazon.awssdk.services.ec2.Ec2Client.class));
        service.deleteResource("111122223333", "vol-1", "EBS Volume", "us-east-1", false, null);
        verifyNoInteractions(spotGroups);
        verify(audit, never()).write(any());
    }
}
