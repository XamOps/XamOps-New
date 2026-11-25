package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.ModifyInstanceAttributeRequest;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest;
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest;
import software.amazon.awssdk.services.ec2.waiters.Ec2Waiter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA depth-testing: failure recovery of the EC2 stop/resize/start workflow
 * (report 02-bugs-B, BUG B-07).
 *
 * <p>The workflow only restarts the instance if the stop-wait completed. If the waiter throws or
 * times out after {@code stopInstances} was accepted, the instance is left powered off.
 */
class RightsizingApplyServiceFailureQaTest {

    private static final String ID = "i-0abc";
    private Ec2Client ec2;
    private Ec2Waiter waiter;
    private RightsizingApplyService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        AwsClientProvider provider = mock(AwsClientProvider.class);
        service = new RightsizingApplyService(provider, mock(CloudAccountRepository.class));
        account = new CloudAccount();
        account.setAwsAccountId("111122223333");
        ec2 = mock(Ec2Client.class);
        waiter = mock(Ec2Waiter.class);
        when(provider.getEc2Client(account, "us-east-1")).thenReturn(ec2);
        when(ec2.waiter()).thenReturn(waiter);
        when(ec2.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(
                DescribeInstancesResponse.builder().reservations(Reservation.builder()
                        .instances(Instance.builder().instanceId(ID).instanceType("m5.large").build()).build())
                        .build());
    }

    @Test
    @DisplayName("characterization: failure after a completed stop restarts the instance")
    void restartsWhenModifyFailsAfterStop() {
        when(ec2.modifyInstanceAttribute(any(ModifyInstanceAttributeRequest.class)))
                .thenThrow(SdkClientException.create("boom"));

        service.performRightsizingAsync(account, ID, "m5.medium", "us-east-1");

        verify(ec2, times(1)).startInstances(any(StartInstancesRequest.class));
    }

    @Test
    @DisplayName("characterization: happy path stops, modifies, then starts once")
    void happyPath() {
        service.performRightsizingAsync(account, ID, "m5.medium", "us-east-1");

        verify(ec2).stopInstances(any(StopInstancesRequest.class));
        verify(ec2).modifyInstanceAttribute(any(ModifyInstanceAttributeRequest.class));
        verify(ec2, times(1)).startInstances(any(StartInstancesRequest.class));
    }

    @Test
    @DisplayName("characterization: describe failure happens before any stop")
    void noStopWhenDescribeFails() {
        when(ec2.describeInstances(any(DescribeInstancesRequest.class))).thenThrow(SdkClientException.create("x"));

        service.performRightsizingAsync(account, ID, "m5.medium", "us-east-1");

        verify(ec2, never()).stopInstances(any(StopInstancesRequest.class));
    }

    @Test
    @Disabled("BUG B-07: if the stop-wait throws/times out after stopInstances was accepted, the instance is never restarted")
    @DisplayName("a stop-wait failure must still try to restart the instance")
    void restartsWhenStopWaitFails() {
        when(waiter.waitUntilInstanceStopped(any(DescribeInstancesRequest.class)))
                .thenThrow(SdkClientException.create("waiter timeout"));

        service.performRightsizingAsync(account, ID, "m5.medium", "us-east-1");

        verify(ec2, times(1)).startInstances(any(StartInstancesRequest.class));
    }
}
