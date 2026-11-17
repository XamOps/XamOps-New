package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeInstanceRefreshesRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeInstanceRefreshesResponse;
import software.amazon.awssdk.services.autoscaling.model.InstanceRefresh;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/** plan-phase-12.md 12.2: status in at most four calls per group per sweep; NOT_FOUND is not PERMISSION. */
class AwsGroupStatusReaderTest {

    private final AwsGroupStatusReader reader = new AwsGroupStatusReader();

    @Test
    void readsInAtMostFourCallsAndReportsTheCapacitySplit() {
        FakeAws aws = new FakeAws().happyPath(FakeAws.asg("web-asg").build());
        doReturn(DescribeInstancesResponse.builder().reservations(Reservation.builder().instances(
                Instance.builder().instanceId("i-1").instanceLifecycle("spot").build(),
                Instance.builder().instanceId("i-2").build(), Instance.builder().instanceId("i-3").build(),
                Instance.builder().instanceId("i-4").build()).build()).build())
                .when(aws.ec2).describeInstances(any(DescribeInstancesRequest.class));

        GroupStatus status = reader.read(aws.view(), "web-asg");

        assertThat(status.found()).isTrue();
        assertThat(status.liveFingerprint()).matches("[0-9a-f]{64}");
        assertThat(status.capacityJson()).contains("\"desired\":4").contains("\"spot\":1").contains("\"onDemand\":3");
        assertThat(status.healthJson()).contains("\"inService\":4").contains("\"refreshInProgress\":false");
        assertThat(status.foreignActivity()).isFalse();
        int calls = aws.calls("describeAutoScalingGroups") + aws.calls("describeScalingActivities")
                + aws.calls("describeInstanceRefreshes") + aws.calls("describeInstances");
        assertThat(calls).isEqualTo(4);
        aws.assertReadOnly();
    }

    @Test
    void theSplitIsCachedSoTheNextSweepMakesThreeCalls() {
        FakeAws aws = new FakeAws().happyPath(FakeAws.asg("web-asg").build());
        reader.read(aws.view(), "web-asg");
        reader.read(aws.view(), "web-asg");
        assertThat(aws.calls("describeInstances")).isEqualTo(1);
        assertThat(aws.calls("describeAutoScalingGroups")).isEqualTo(2);
    }

    @Test
    void aRunningInstanceRefreshIsForeignActivity() {
        FakeAws aws = new FakeAws().happyPath(FakeAws.asg("web-asg").build());
        doReturn(DescribeInstanceRefreshesResponse.builder().instanceRefreshes(
                InstanceRefresh.builder().status("InProgress").build()).build())
                .when(aws.asg).describeInstanceRefreshes(any(DescribeInstanceRefreshesRequest.class));
        GroupStatus status = reader.read(aws.view(), "web-asg");
        assertThat(status.foreignActivity()).isTrue();
        assertThat(status.healthJson()).contains("\"refreshInProgress\":true");
    }

    @Test
    void aMissingGroupIsNotFound() {
        FakeAws aws = new FakeAws().happyPath();
        GroupStatus status = reader.read(aws.view(), "gone-asg");
        assertThat(status.found()).isFalse();
    }

    @Test
    void aPermissionFailureIsPermissionNeverNotFound() {
        FakeAws aws = new FakeAws().happyPath(FakeAws.asg("web-asg").build());
        doThrow(AutoScalingException.builder().statusCode(403).message("denied").build())
                .when(aws.asg).describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class));
        assertThatThrownBy(() -> reader.read(aws.view(), "web-asg"))
                .isInstanceOfSatisfying(AwsReadException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.PERMISSION));
    }

    @Test
    void errorKindsAreTranslatedAtTheBoundary() {
        assertThat(AwsErrors.classify(AutoScalingException.builder().statusCode(503).build()))
                .isEqualTo(ErrorKind.TRANSIENT);
        assertThat(AwsErrors.classify(AutoScalingException.builder().statusCode(404).build()))
                .isEqualTo(ErrorKind.NOT_FOUND);
        assertThat(AwsErrors.classify(AutoScalingException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("Throttling").build()).build()))
                .isEqualTo(ErrorKind.THROTTLED);
        assertThat(AwsErrors.classify(AutoScalingException.builder().statusCode(400).build()))
                .isEqualTo(ErrorKind.VALIDATION);
        assertThat(new AwsReadException("x", ErrorKind.PERMISSION).toString()).doesNotContain("denied");
    }
}
