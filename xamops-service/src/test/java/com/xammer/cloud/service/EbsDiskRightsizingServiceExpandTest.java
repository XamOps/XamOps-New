package com.xammer.cloud.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks EbsDiskRightsizingService service;

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
}
