package com.xammer.cloud.service.autospotting.aws.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AC-EVT-05: the policy is rebuilt from CONNECTED accounts; a disconnected account drops out on the next sync. */
class AutoSpottingQueueAuthorizerTest {

    private final FakeSqsClient sqs = new FakeSqsClient();
    private final CloudAccountRepository repo = mock(CloudAccountRepository.class);
    private final List<CloudAccount> accounts = new ArrayList<>();
    private AutoSpottingQueueAuthorizer authorizer;

    private static CloudAccount acct(String awsId, String status) {
        CloudAccount a = new CloudAccount();
        a.setProvider("AWS");
        a.setStatus(status);
        a.setAwsAccountId(awsId);
        return a;
    }

    @BeforeEach
    void setUp() {
        SchedulingEnvironmentConfig env = mock(SchedulingEnvironmentConfig.class);
        when(env.getCurrentTenantId()).thenReturn("customer1");
        when(repo.findAll()).thenAnswer(i -> new ArrayList<>(accounts));
        authorizer = new AutoSpottingQueueAuthorizer(sqs, repo, env, new ObjectMapper(),
                new AwsEventProperties(false, true, FakeSqsClient.QUEUE_URL, true), mock(JobRunner.class),
                mock(LeaseProperties.class), "arn:aws:iam::000000000000:role/backend");
    }

    @Test
    void aDisconnectedAccountDropsOutOnTheNextSync() {
        CloudAccount a = acct("111111111111", "CONNECTED");
        accounts.add(a);
        accounts.add(acct("222222222222", "CONNECTED"));
        accounts.add(acct("333333333333", "PENDING"));
        authorizer.syncQueuePolicy();
        assertThat(sqs.policy).contains("111111111111", "222222222222").doesNotContain("333333333333")
                .contains("XamOpsAutoSpottingBackendConsume");
        a.setStatus("DISCONNECTED");
        authorizer.syncQueuePolicy();
        assertThat(sqs.policy).doesNotContain("111111111111").contains("222222222222");
    }

    @Test
    void anEmptyAccountListLeavesThePolicyUnchanged() {
        sqs.policy = "{\"existing\":true}";
        authorizer.syncQueuePolicy();
        assertThat(sqs.policy).isEqualTo("{\"existing\":true}");
    }

    @Test
    void anUnchangedAccountSetIsNotRewritten() {
        accounts.add(acct("111111111111", "CONNECTED"));
        authorizer.syncQueuePolicy();
        sqs.calls.clear();
        authorizer.syncQueuePolicy();
        assertThat(sqs.calls).noneMatch(c -> c.startsWith("setAttributes"));
    }

    @Test
    void aRefusedQueueIsNeverWritten() {
        SchedulingEnvironmentConfig env = mock(SchedulingEnvironmentConfig.class);
        AutoSpottingQueueAuthorizer prodQueueInUat = new AutoSpottingQueueAuthorizer(sqs, repo, env, new ObjectMapper(),
                new AwsEventProperties(false, true,
                        "https://sqs.ap-south-1.amazonaws.com/1/xamops-autospotting-events.fifo", true),
                mock(JobRunner.class), mock(LeaseProperties.class), "");
        accounts.add(acct("111111111111", "CONNECTED"));
        prodQueueInUat.syncQueuePolicy();
        assertThat(sqs.calls).isEmpty();
    }

    @Test
    void nearTheSizeLimitThePolicySwitchesToTheRolePattern() throws Exception {
        String pattern = authorizer.buildPatternPolicy(FakeSqsClient.QUEUE_URL);
        assertThat(pattern).contains("aws:PrincipalArn").contains("role/xamops-as-events-fwd-role-uat")
                .doesNotContain("aws:PrincipalAccount");
        for (int i = 0; i < 700; i++) {
            accounts.add(acct(String.format("%012d", i + 1), "CONNECTED"));
        }
        authorizer.syncQueuePolicy();
        assertThat(sqs.policy).contains("aws:PrincipalArn").doesNotContain("aws:PrincipalAccount");
    }
}
