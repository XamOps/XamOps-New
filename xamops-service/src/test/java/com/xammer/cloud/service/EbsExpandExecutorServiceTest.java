package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.EbsVolumeAction;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EbsExpandExecutorServiceTest {

    @Mock AwsClientProvider awsClientProvider;
    @Mock CloudAccountRepository cloudAccountRepository;
    @Mock EbsVolumeActionRepository ebsVolumeActionRepository;
    @Mock RedisCacheService redisCacheService;
    @Mock EbsAsgHelper ebsAsgHelper;
    @Mock Ec2Client ec2;
    @Mock SsmClient ssm;

    @InjectMocks EbsExpandExecutorService service;

    CloudAccount account;
    EbsVolumeAction action;

    @BeforeEach
    void setUp() {
        account = new CloudAccount();
        account.setId(1L);

        action = new EbsVolumeAction();
        action.setId(10L);

        when(cloudAccountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(ebsVolumeActionRepository.findById(10L)).thenReturn(Optional.of(action));
        // Use lenient() to avoid UnnecessaryStubbingException in tests that validate and fail early
        lenient().when(awsClientProvider.getEc2Client(account, "us-east-1")).thenReturn(ec2);
        lenient().when(awsClientProvider.getSsmClient(account, "us-east-1")).thenReturn(ssm);
    }

    @Test
    void executeExpandWorkflow_nonAsg_runsThreeSteps() throws InterruptedException {
        when(ec2.modifyVolume(any(ModifyVolumeRequest.class)))
                .thenReturn(ModifyVolumeResponse.builder()
                        .volumeModification(VolumeModification.builder()
                                .volumeId("vol-001")
                                .modificationState(VolumeModificationState.COMPLETED)
                                .build())
                        .build());

        // DescribeVolumesModifications returns completed immediately (no sleep needed in test)
        when(ec2.describeVolumesModifications(any(DescribeVolumesModificationsRequest.class)))
                .thenReturn(DescribeVolumesModificationsResponse.builder()
                        .volumesModifications(VolumeModification.builder()
                                .volumeId("vol-001")
                                .modificationState(VolumeModificationState.COMPLETED)
                                .build())
                        .build());

        when(ssm.sendCommand(any(SendCommandRequest.class)))
                .thenReturn(SendCommandResponse.builder()
                        .command(Command.builder().commandId("cmd-001").build())
                        .build());
        when(ssm.getCommandInvocation(any(GetCommandInvocationRequest.class)))
                .thenReturn(GetCommandInvocationResponse.builder()
                        .status(CommandInvocationStatus.SUCCESS)
                        .standardOutputContent("resize done")
                        .build());

        // Valid instance ID: i-[8-17 hex chars]
        service.executeExpandWorkflow(10L, 1L, "vol-001", "us-east-1", "i-0abcdef123456789", "/dev/xvdf", 300, null);

        verify(ec2).modifyVolume(argThat((ModifyVolumeRequest req) ->
                "vol-001".equals(req.volumeId()) && req.size() == 300));
        verify(ssm).sendCommand(any(SendCommandRequest.class));
        verify(ebsVolumeActionRepository, atLeastOnce()).save(argThat((EbsVolumeAction a) ->
                "SUCCESS".equals(a.getStatus())));
    }

    @Test
    void executeExpandWorkflow_setsFailedStatusOnException() {
        when(ec2.modifyVolume(any(ModifyVolumeRequest.class)))
                .thenThrow(new RuntimeException("API throttled"));

        // Valid instance ID
        service.executeExpandWorkflow(10L, 1L, "vol-001", "us-east-1", "i-0abcdef123456789", "/dev/xvdf", 300, null);

        verify(ebsVolumeActionRepository, atLeastOnce()).save(argThat((EbsVolumeAction a) ->
                "FAILED".equals(a.getStatus())));
    }

    @Test
    void executeExpandWorkflow_rejectsInvalidDevicePath() {
        // Device path with shell metacharacters should be rejected before any SSM command is sent
        // Valid instance ID but invalid device
        service.executeExpandWorkflow(10L, 1L, "vol-001", "us-east-1", "i-0abcdef123456789", "/dev/xvdf; rm -rf /", 300, null);

        // Validation throws inside the try block → caught → action saved as FAILED
        verify(ebsVolumeActionRepository, atLeastOnce()).save(argThat((EbsVolumeAction a) ->
                "FAILED".equals(a.getStatus())));
        // modifyVolume must NOT have been called (should fail before reaching SSM)
        verifyNoInteractions(ssm);
    }
}
