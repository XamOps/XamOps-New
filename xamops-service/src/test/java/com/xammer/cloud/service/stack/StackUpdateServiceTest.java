package com.xammer.cloud.service.stack;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.AwsClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ExecuteChangeSetRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the applyAll orchestration: whether the connection change set is executed is decided
 * by the change set id, while the CUR half always runs through {@link CurStackService} (which
 * is mocked here, since its own behaviour is covered by {@link CurStackServiceTest}).
 */
class StackUpdateServiceTest {

    private final AwsClientProvider awsClientProvider = mock(AwsClientProvider.class);
    private final StackDiagnosisService diagnosisService = mock(StackDiagnosisService.class);
    private final CurStackService curStackService = mock(CurStackService.class);
    private final CloudFormationClient cfn = mock(CloudFormationClient.class);

    private StackUpdateService service;

    @BeforeEach
    void setUp() {
        service = new StackUpdateService(awsClientProvider, diagnosisService, curStackService,
                "", "https://s3.example/ro.yml", "https://s3.example/rw.yml", "ap-south-1");
        when(awsClientProvider.getCloudFormationClient(any(CloudAccount.class), any())).thenReturn(cfn);
    }

    private CloudAccount account() {
        CloudAccount account = new CloudAccount("Acme Prod", "ext-1", "read-only", mock(Client.class));
        account.setAwsAccountId("111122223333");
        return account;
    }

    @Test
    void applyAll_executesConnectionChangeSet_whenChangeSetIdPresent() {
        CurStackService.CurApplyResult curResult = new CurStackService.CurApplyResult(
                CurStackService.CurStatus.CREATE_IN_PROGRESS, "XamOps-CUR-AcmeProd", "Creating.");
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean())).thenReturn(curResult);

        StackUpdateService.ApplyResult result = service.applyAll(account(), "cs-123");

        assertThat(result.connectionStarted()).isTrue();
        assertThat(result.cur()).isEqualTo(curResult);
        verify(cfn).executeChangeSet(any(ExecuteChangeSetRequest.class));
        verify(curStackService).applyAsync(any(CloudAccount.class), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void applyAll_skipsConnectionChangeSet_whenChangeSetIdNull() {
        CurStackService.CurApplyResult curResult = new CurStackService.CurApplyResult(
                CurStackService.CurStatus.UPDATE_IN_PROGRESS, "XamOps-CUR-AcmeProd", "Updating.");
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean())).thenReturn(curResult);

        StackUpdateService.ApplyResult result = service.applyAll(account(), null);

        assertThat(result.connectionStarted()).isFalse();
        assertThat(result.cur()).isEqualTo(curResult);
        verify(cfn, never()).executeChangeSet(any(ExecuteChangeSetRequest.class));
        verify(curStackService).applyAsync(any(CloudAccount.class), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void applyAll_skipsConnectionChangeSet_whenChangeSetIdBlank() {
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(new CurStackService.CurApplyResult(
                        CurStackService.CurStatus.SKIPPED, "XamOps-CUR-AcmeProd", "Skipped."));

        StackUpdateService.ApplyResult result = service.applyAll(account(), "   ");

        assertThat(result.connectionStarted()).isFalse();
        verify(cfn, never()).executeChangeSet(any(ExecuteChangeSetRequest.class));
    }
}
