package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.stack.CurStackService.CurApplyResult;
import com.xammer.cloud.service.stack.CurStackService.CurStatus;
import com.xammer.cloud.service.stack.StackSequenceService;
import com.xammer.cloud.service.stack.StackSequenceService.SequenceProgress;
import com.xammer.cloud.service.stack.StackUpdateService.ApplyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused coverage of the two stack-update endpoints repointed onto {@link StackSequenceService}:
 * {@code execute} returns 202 with the seed {@code ApplyResult}, and {@code progress} returns the
 * two-phase map including {@code done}. The controller's heavy {@code @Autowired} graph is set via
 * reflection for just the fields these two endpoints touch.
 */
class AccountManagerControllerStackSequenceTest {

    private static final String ACCOUNT_ID = "111122223333";

    private final CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
    private final StackSequenceService stackSequenceService = mock(StackSequenceService.class);

    private AccountManagerController controller;

    @BeforeEach
    void setUp() {
        controller = new AccountManagerController();
        ReflectionTestUtils.setField(controller, "cloudAccountRepository", cloudAccountRepository);
        ReflectionTestUtils.setField(controller, "stackSequenceService", stackSequenceService);

        CloudAccount account = new CloudAccount("Acme Prod", "ext-1", "read-only", mock(Client.class));
        account.setAwsAccountId(ACCOUNT_ID);
        account.setProvider("AWS");
        when(cloudAccountRepository.findByAwsAccountId(ACCOUNT_ID)).thenReturn(List.of(account));
    }

    @Test
    void execute_returns202WithSeed() {
        CurApplyResult seedCur = new CurApplyResult(CurStatus.WAITING_FOR_CONNECTION, "XamOps-CUR-Acme", "waiting");
        when(stackSequenceService.start(any(CloudAccount.class), any()))
                .thenReturn(new ApplyResult(true, seedCur));

        ResponseEntity<?> response = controller.executeStackUpdate(ACCOUNT_ID, Map.of("changeSetId", "cs-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isInstanceOf(ApplyResult.class);
        assertThat(((ApplyResult) response.getBody()).cur().curStatus())
                .isEqualTo(CurStatus.WAITING_FOR_CONNECTION);
    }

    @Test
    @SuppressWarnings("unchecked")
    void progress_returnsTwoPhaseMapWithDone() {
        SequenceProgress progress = mock(SequenceProgress.class);
        when(progress.toResponseMap()).thenReturn(Map.of(
                "status", "UPDATE_IN_PROGRESS",
                "inProgress", true,
                "connectionPhase", "UPDATING",
                "done", false));
        when(stackSequenceService.progress(any(CloudAccount.class), anyInt())).thenReturn(progress);

        ResponseEntity<?> response = controller.stackUpdateProgress(ACCOUNT_ID, 25);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("done", false);
        assertThat(body).containsEntry("connectionPhase", "UPDATING");
    }
}
