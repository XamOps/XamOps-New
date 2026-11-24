package com.xammer.cloud.service.stack;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
/** The stack's {@code TemplateVersion} output reaches {@code cloud_account.stack_template_version} (AC-ONB-01). */
class StackDiagnosisServiceTest {
    private final CloudAccountRepository repository = mock(CloudAccountRepository.class);
    private final AwsClientProvider provider = mock(AwsClientProvider.class);
    private final CloudFormationClient cfn = mock(CloudFormationClient.class);
    private StackDiagnosisService service;
    @BeforeEach
    void setUp() {
        service = new StackDiagnosisService(repository, provider, "ap-south-1");
        when(provider.getCloudFormationClient(any(CloudAccount.class), any())).thenReturn(cfn);
    }
    private CloudAccount account() {
        CloudAccount account = new CloudAccount("Acme", "ext-1", "read-write", mock(Client.class));
        account.setAwsAccountId("111122223333");
        account.setRoleArn("arn:aws:iam::111122223333:role/XamOpsReadWriteRole");
        account.setStackId("arn:aws:cloudformation:us-east-1:111122223333:stack/XamOps/abc");
        account.setStackRegion("us-east-1");
        return account;
    }
    private void stackWith(Output... outputs) {
        Stack stack = Stack.builder().stackId("arn:aws:cloudformation:us-east-1:111122223333:stack/XamOps/abc")
                .stackName("XamOps").stackStatus("UPDATE_COMPLETE").outputs(outputs).build();
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(DescribeStacksResponse.builder().stacks(stack).build());
    }
    private static Output output(String key, String value) {
        return Output.builder().outputKey(key).outputValue(value).build();
    }
    @Test
    void theTemplateVersionOutputIsStoredOnTheAccount() {
        stackWith(output("XamOpsRoleArn", "arn"), output("TemplateVersion", "rw-2026.11.1"));
        CloudAccount account = account();
        StackDiagnosisService.Diagnosis d = service.diagnose(account);
        assertThat(d.verdict()).isEqualTo(StackDiagnosisService.Verdict.HEALTHY);
        assertThat(d.templateVersion()).isEqualTo("rw-2026.11.1");
        assertThat(account.getStackTemplateVersion()).isEqualTo("rw-2026.11.1");
    }
    @Test
    void aStackWithoutTheOutputLeavesTheColumnUnchanged() {
        stackWith(output("XamOpsRoleArn", "arn"));
        CloudAccount account = account();
        account.setStackTemplateVersion(null);
        service.diagnose(account);
        assertThat(account.getStackTemplateVersion()).isNull();
    }
    @Test
    void anOutputThatIsNotAVersionIsIgnored() {
        stackWith(output("TemplateVersion", "anything; DROP TABLE"));
        CloudAccount account = account();
        service.diagnose(account);
        assertThat(account.getStackTemplateVersion()).isNull();
    }
}
