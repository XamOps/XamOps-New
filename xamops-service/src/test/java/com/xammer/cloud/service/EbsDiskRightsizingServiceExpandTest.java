package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.EbsVolumeAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EbsDiskRightsizingServiceExpandTest {

    @Mock Ec2Client ec2;
    @Mock AwsClientProvider awsClientProvider;
    @Mock com.xammer.cloud.repository.CloudAccountRepository cloudAccountRepository;
    @Mock com.xammer.cloud.repository.DiskPolicyRepository diskPolicyRepository;
    @Mock com.xammer.cloud.repository.EbsVolumeActionRepository ebsVolumeActionRepository;
    @Mock RedisCacheService redisCacheService;
    @Mock EbsShrinkExecutorService shrinkExecutorService;
    @Mock EbsExpandExecutorService expandExecutorService;
    @Mock EbsAsgHelper ebsAsgHelper;

    @InjectMocks EbsDiskRightsizingService service;

    @BeforeEach
    void setUpExpand() {
        CloudAccount account = new CloudAccount();
        account.setId(42L);
        account.setAwsAccountId("123456789012");
        // lenient: these stubs are only consumed by the initiateExpand test, not the enrichWithAsgNames tests
        lenient().when(cloudAccountRepository.findByProviderAccountId("42")).thenReturn(Optional.of(account));
        lenient().when(awsClientProvider.getEc2Client(any(), any())).thenReturn(ec2);
        lenient().when(ec2.describeVolumes(any(DescribeVolumesRequest.class)))
                .thenReturn(DescribeVolumesResponse.builder()
                        .volumes(Volume.builder()
                                .volumeId("vol-expand")
                                .size(200)
                                .volumeType(VolumeType.GP3)
                                .attachments(VolumeAttachment.builder()
                                        .instanceId("i-0abcdef123456789")
                                        .device("/dev/xvdf")
                                        .build())
                                .build())
                        .build());
        lenient().when(ebsVolumeActionRepository.save(any())).thenAnswer(inv -> {
            EbsVolumeAction a = inv.getArgument(0);
            a.setId(77L);
            return a;
        });
    }

    @Test
    void enrichWithAsgNames_setsAsgNameOnAttachedVolumes() {
        Map<String, Object> vol = new HashMap<>();
        vol.put("volumeId", "vol-aaa");
        vol.put("instanceId", "i-111");
        List<Map<String, Object>> volumes = List.of(vol);

        Instance inst = Instance.builder()
                .instanceId("i-111")
                .tags(Tag.builder().key("aws:autoscaling:groupName").value("my-asg").build())
                .build();
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(DescribeInstancesResponse.builder()
                        .reservations(Reservation.builder().instances(inst).build())
                        .build());

        service.enrichWithAsgNames(ec2, volumes);

        assertThat(vol).containsEntry("asgName", "my-asg");
    }

    @Test
    void enrichWithAsgNames_skipsVolumesWithNoInstanceId() {
        Map<String, Object> unattached = new HashMap<>();
        unattached.put("volumeId", "vol-bbb");
        List<Map<String, Object>> volumes = List.of(unattached);

        service.enrichWithAsgNames(ec2, volumes);

        assertThat(unattached).doesNotContainKey("asgName");
        verifyNoInteractions(ec2);
    }

    @Test
    void enrichWithAsgNames_doesNotSetAsgNameWhenNoMatchingTag() {
        Map<String, Object> vol = new HashMap<>();
        vol.put("volumeId", "vol-ccc");
        vol.put("instanceId", "i-333");
        List<Map<String, Object>> volumes = List.of(vol);

        Instance inst = Instance.builder()
                .instanceId("i-333")
                .tags(Tag.builder().key("Name").value("my-instance").build())
                .build();
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(DescribeInstancesResponse.builder()
                        .reservations(Reservation.builder().instances(inst).build())
                        .build());

        service.enrichWithAsgNames(ec2, volumes);

        assertThat(vol).doesNotContainKey("asgName");
    }

    @Test
    void enrichWithAsgNames_swallowsExceptionWithoutPropagating() {
        Map<String, Object> vol = new HashMap<>();
        vol.put("volumeId", "vol-ddd");
        vol.put("instanceId", "i-444");
        List<Map<String, Object>> volumes = List.of(vol);

        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenThrow(new RuntimeException("AWS timeout"));

        assertThatCode(() -> service.enrichWithAsgNames(ec2, volumes))
                .doesNotThrowAnyException();
        assertThat(vol).doesNotContainKey("asgName");
    }

    @Test
    void applyAction_upgradeType_asgPath_updatesLaunchTemplateAndRefreshes() {
        when(ec2.modifyVolume(any(ModifyVolumeRequest.class)))
                .thenReturn(ModifyVolumeResponse.builder()
                        .volumeModification(VolumeModification.builder()
                                .volumeId("vol-upgrade")
                                .modificationState(VolumeModificationState.COMPLETED)
                                .build())
                        .build());
        when(ec2.describeVolumes(any(DescribeVolumesRequest.class)))
                .thenReturn(DescribeVolumesResponse.builder()
                        .volumes(Volume.builder()
                                .volumeId("vol-upgrade")
                                .size(100)
                                .volumeType(VolumeType.GP2)
                                .attachments(VolumeAttachment.builder()
                                        .instanceId("i-0abcdef123456789")
                                        .device("/dev/xvda")
                                        .build())
                                .build())
                        .build());

        Map<String, Object> result = service.applyAction(
                "42", "vol-upgrade", "us-east-1",
                "UPGRADE_TYPE", "gp3", null, "i-0abcdef123456789", "my-asg");

        assertThat(result).containsEntry("success", true);
        verify(ebsAsgHelper).updateLaunchTemplateVolumeType(
                any(), any(), eq("my-asg"), eq("/dev/xvda"), eq("gp3"));
        verify(ebsAsgHelper).startInstanceRefresh(any(), eq("my-asg"));
    }

    @Test
    void initiateExpand_createsActionRecordWithPendingStatus() {
        Map<String, Object> result = service.initiateExpand(
                "42", "vol-expand", "us-east-1", "i-0abcdef123456789", "/dev/xvdf", 300, null);

        assertThat(result).containsEntry("success", true);
        assertThat(result).containsKey("actionId");
        verify(ebsVolumeActionRepository).save(argThat((EbsVolumeAction a) ->
                "PENDING".equals(a.getStatus()) && "EXPAND".equals(a.getActionType())));
        verify(expandExecutorService).executeExpandWorkflow(
                anyLong(), eq(42L), eq("vol-expand"), eq("us-east-1"),
                eq("i-0abcdef123456789"), eq("/dev/xvdf"), eq(300), isNull());
    }
}
