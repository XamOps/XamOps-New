package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link StackReader} over the connection role's CloudFormation client. Only DescribeStacks and DescribeStackEvents. */
@Component("xpAwsStackReader")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class AwsStackReader implements StackReader {

    private static final int MAX_EVENTS = 10;
    private final AwsClientProvider aws;

    public AwsStackReader(AwsClientProvider aws) {
        this.aws = aws;
    }

    @Override
    public Optional<Stack> describe(CloudAccount account, String region, String stackName) {
        try {
            CloudFormationClient cfn = aws.getCloudFormationClient(account, region);
            var stacks = cfn.describeStacks(r -> r.stackName(stackName)).stacks();
            if (stacks.isEmpty()) {
                return Optional.empty();
            }
            var s = stacks.get(0);
            Map<String, String> outputs = new LinkedHashMap<>();
            for (Output o : s.outputs()) {
                outputs.put(o.outputKey(), o.outputValue());
            }
            List<String> events = new ArrayList<>();
            String status = s.stackStatusAsString();
            if (!"CREATE_COMPLETE".equals(status) && !"UPDATE_COMPLETE".equals(status)) {
                for (StackEvent e : cfn.describeStackEvents(r -> r.stackName(stackName)).stackEvents()) {
                    String st = e.resourceStatusAsString();
                    if (st != null && (st.endsWith("_FAILED") || e.resourceStatusReason() != null)) {
                        events.add(e.logicalResourceId() + " " + st + (e.resourceStatusReason() == null ? "" : ": " + e.resourceStatusReason()));
                        if (events.size() >= MAX_EVENTS) {
                            break;
                        }
                    }
                }
            }
            return Optional.of(new Stack(status, s.stackStatusReason(), s.stackId(), outputs, events));
        } catch (CloudFormationException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (e.statusCode() == 400 && msg.contains("does not exist")) {
                return Optional.empty();
            }
            throw new XamPulseException("XP_AWS_UNAVAILABLE", 502, "Could not read the stack from AWS: "
                    + (e.awsErrorDetails() == null ? "error" : e.awsErrorDetails().errorCode()));
        } catch (RuntimeException e) {
            throw new XamPulseException("XP_AWS_UNAVAILABLE", 502, "Could not reach AWS to read the stack.");
        }
    }
}
