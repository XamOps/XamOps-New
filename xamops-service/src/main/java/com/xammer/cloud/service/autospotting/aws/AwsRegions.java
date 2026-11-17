package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;

import java.util.ArrayList;
import java.util.List;

/** The regions enabled for an account ({@code ec2:DescribeRegions}, read only). */
final class AwsRegions {

    /** Any enabled region works for the call; {@code us-east-1} is always available. */
    static final String ENTRY_REGION = "us-east-1";

    private AwsRegions() {
    }

    /** The enabled regions, sorted. Throws {@link AwsReadException} when they cannot be read. */
    static List<String> enabled(AwsViewSource views, CloudAccount account) {
        AwsReadOnlyView any = views.view(account, ENTRY_REGION);
        List<String> regions = new ArrayList<>();
        AwsErrors.read("DescribeRegions", () -> any.ec2().describeRegions())
                .regions().forEach(r -> regions.add(r.regionName()));
        regions.sort(String::compareTo);
        return regions;
    }
}
