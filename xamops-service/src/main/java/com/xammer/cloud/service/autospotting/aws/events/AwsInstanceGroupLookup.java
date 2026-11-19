package com.xammer.cloud.service.autospotting.aws.events;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsErrors;
import com.xammer.cloud.service.autospotting.aws.AwsReadException;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsViewSource;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingInstanceDetails;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.TagDescription;

import java.time.Duration;
import java.util.Optional;

/** {@link InstanceGroupLookup} through the customer's read session. */
@Component
public class AwsInstanceGroupLookup implements InstanceGroupLookup {

    static final String GROUP_TAG = "aws:autoscaling:groupName";
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AwsViewSource views;

    public AwsInstanceGroupLookup(AwsViewSource views) {
        this.views = views;
    }

    @Override
    public Optional<String> groupName(CloudAccount account, String region, String instanceId) {
        AwsReadOnlyView view = views.view(account, region);
        Optional<String> fromAsg;
        try {
            fromAsg = AwsErrors.read("DescribeAutoScalingInstances", () -> view.autoScaling()
                    .describeAutoScalingInstances(r -> r.instanceIds(instanceId)
                            .overrideConfiguration(o -> o.apiCallTimeout(TIMEOUT)))
                    .autoScalingInstances().stream().findFirst()
                    .map(AutoScalingInstanceDetails::autoScalingGroupName));
        } catch (AwsReadException e) {
            if (e.kind() != ErrorKind.NOT_FOUND) {
                throw e;
            }
            fromAsg = Optional.empty();
        }
        if (fromAsg.isPresent()) {
            return fromAsg;
        }
        return AwsErrors.read("DescribeTags", () -> view.ec2().describeTags(r -> r
                        .filters(Filter.builder().name("resource-id").values(instanceId).build(),
                                Filter.builder().name("key").values(GROUP_TAG).build())
                        .overrideConfiguration(o -> o.apiCallTimeout(TIMEOUT)))
                .tags().stream().findFirst().map(TagDescription::value));
    }
}
