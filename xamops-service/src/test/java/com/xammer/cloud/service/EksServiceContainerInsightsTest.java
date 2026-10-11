package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.ContainerInsightsStatusDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Container Insights is CONFIGURED only on an exact log-group match from the one existing
 * {@code DescribeLogGroups} call (design D3); no new AWS call is made.
 */
class EksServiceContainerInsightsTest {

    private static final String GROUP = "/aws/containerinsights/prod-eks/performance";

    private CloudWatchLogsClient logs;
    private EksService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        logs = mock(CloudWatchLogsClient.class);
        AwsClientProvider clients = mock(AwsClientProvider.class);
        account = new CloudAccount();
        account.setId(7L);
        when(clients.getCloudWatchLogsClient(any(), anyString())).thenReturn(logs);
        service = new EksService(mock(CloudAccountRepository.class), clients, mock(CloudListService.class),
                mock(RedisCacheService.class));
    }

    @SuppressWarnings("unchecked")
    private void groups(String... names) {
        DescribeLogGroupsResponse.Builder b = DescribeLogGroupsResponse.builder();
        b.logGroups(java.util.Arrays.stream(names).map(n -> LogGroup.builder().logGroupName(n).build()).toList());
        when(logs.describeLogGroups(any(Consumer.class))).thenReturn(b.build());
    }

    @SuppressWarnings("unchecked")
    private void fails(String errorCode, int status) {
        when(logs.describeLogGroups(any(Consumer.class))).thenThrow(CloudWatchLogsException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).errorMessage("denied for arn:secret").build())
                .statusCode(status)
                .message("denied for arn:secret")
                .build());
    }

    private ContainerInsightsStatusDto check() {
        return service.checkContainerInsightsStatus(account, "prod-eks", "us-east-1");
    }

    @Test
    void anExactMatchIsConfigured() {
        groups(GROUP);
        ContainerInsightsStatusDto s = check();
        assertThat(s.getStatus()).isEqualTo("CONFIGURED");
        assertThat(s.isConfigured()).isTrue();
        assertThat(s.getReason()).isNull();
        assertThat(Instant.parse(s.getCheckedAt())).isNotNull();
    }

    @Test
    void onlyALongerNameIsNotConfigured() {
        groups(GROUP + "-old");
        assertThat(check().getStatus()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void anEmptyResponseIsNotConfigured() {
        groups();
        assertThat(check().getStatus()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void accessDeniedIsUnknownWithTheMissingPermission() {
        fails("AccessDeniedException", 400);
        ContainerInsightsStatusDto s = check();
        assertThat(s.getStatus()).isEqualTo("UNKNOWN");
        assertThat(s.getReason()).isEqualTo("MISSING_AWS_PERMISSION");
        assertThat(s.getAction()).isEqualTo("logs:DescribeLogGroups");

        fails("Forbidden", 403);
        assertThat(check().getReason()).isEqualTo("MISSING_AWS_PERMISSION");
    }

    @Test
    void throttlingIsUnknownWithoutAReason() {
        fails("ThrottlingException", 400);
        ContainerInsightsStatusDto s = check();
        assertThat(s.getStatus()).isEqualTo("UNKNOWN");
        assertThat(s.getReason()).isNull();
        assertThat(s.getAction()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCheckUsesOnlyTheExistingPrefixCall() {
        groups(GROUP);
        check();

        org.mockito.ArgumentCaptor<Consumer<DescribeLogGroupsRequest.Builder>> request =
                org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(logs).describeLogGroups(request.capture());
        DescribeLogGroupsRequest.Builder builder = DescribeLogGroupsRequest.builder();
        request.getValue().accept(builder);
        assertThat(builder.build().logGroupNamePrefix()).isEqualTo(GROUP);
        verify(logs, never()).describeLogStreams(any(DescribeLogStreamsRequest.class));
        verify(logs, never()).describeLogStreams(any(Consumer.class));
    }
}
