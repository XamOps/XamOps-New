package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.AsgSpotConfig;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.AsgSpotConfigRepository;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.core.discovery.DiscoveredGroup;
import com.xammer.cloud.service.autospotting.core.discovery.DiscoveredGroup.Result;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.ec2.model.ResponseLaunchTemplateData;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateInstanceMarketOptions;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateVersion;
import software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * plan-phase-12.md 12.5, design-core.md §2.5 (AC-SPN-12): rules 1-5 for AWS, zero mutating calls, and an
 * unreadable region is reported, never "no groups".
 */
class AwsGroupDiscoveryTest {

    private final CloudAccount account = FakeAws.account();
    private final GroupLookup lookup = mock(GroupLookup.class);
    private final AsgSpotConfigRepository legacy = mock(AsgSpotConfigRepository.class);
    private final FakeAws aws = new FakeAws();
    private final FakeAws broken = new FakeAws();

    private AwsGroupDiscovery discovery() {
        AwsViewSource views = (a, region) -> "eu-west-1".equals(region) ? broken.view() : aws.view();
        AwsEstimatePriceService prices = new AwsEstimatePriceService(
                mock(software.amazon.awssdk.services.pricing.PricingClient.class), views);
        return new AwsGroupDiscovery(views, lookup, legacy, new AwsPlanReader(prices));
    }

    private static AutoScalingGroup mixedWithSpotShare(String name) {
        return FakeAws.asg(name).launchTemplate((LaunchTemplateSpecification) null)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-1").version("1").build()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandPercentageAboveBaseCapacity(40).build()).build())
                .build();
    }

    private void world(AutoScalingGroup... groups) {
        aws.happyPath(groups);
        doReturn(DescribeRegionsResponse.builder().regions(Region.builder().regionName("ap-south-1").build()).build())
                .when(aws.ec2).describeRegions();
        when(lookup.forAccount(Provider.AWS, 7L)).thenReturn(List.of());
    }

    private static DiscoveredGroup find(Result r, String key) {
        return r.groups().stream().filter(g -> g.groupKey().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void anAsgTaggedSpotEnabledTrueIsLegacyUnconfirmedWithAnInexactSnapshot() {
        AsgSpotConfig cfg = new AsgSpotConfig();
        cfg.setSpotPercentage(70);
        cfg.setSpotAllocationStrategy("capacity-optimized");
        world(FakeAws.asg("legacy-asg").tags(FakeAws.tag("spot-enabled", "true")).build());
        when(legacy.findByAccountIdAndRegionAndAsgName(7L, "ap-south-1", "legacy-asg")).thenReturn(Optional.of(cfg));

        Result r = discovery().discover(account, aws.view());

        DiscoveredGroup g = find(r, "ap-south-1/legacy-asg");
        assertThat(g.matchedRule()).isEqualTo(2);
        assertThat(g.reason()).isNull();
        assertThat(g.reconstructedSnapshot().exact()).isFalse();
        assertThat(g.providerStableId()).contains("autoScalingGroupName/legacy-asg");
        assertThat(g.desiredConfigJson()).contains("\"spotPercentage\":70");
        assertThat(r.failedLocations()).isEmpty();
        aws.assertReadOnly();
    }

    @Test
    void aMarkerWithNoRowIsAnOrphanMarker() {
        world(FakeAws.asg("orphan-asg").tags(FakeAws.tag("xamops:autospotting:group-id", UUID.randomUUID().toString())).build());
        Result r = discovery().discover(account, aws.view());
        DiscoveredGroup g = find(r, "ap-south-1/orphan-asg");
        assertThat(g.matchedRule()).isEqualTo(3);
        assertThat(g.reason()).isEqualTo(DiscoveredGroup.ORPHAN_MARKER);
        assertThat(g.reconstructedSnapshot().exact()).isFalse();
        assertThat(g.hasMarker()).isTrue();
    }

    @Test
    void aMarkerWhoseRowExistsIsNotAnOrphan() {
        UUID id = UUID.randomUUID();
        SpotGroup row = new SpotGroup();
        row.setId(id);
        row.setGroupKey("ap-south-1/known-asg");
        world(FakeAws.asg("known-asg").tags(FakeAws.tag("xamops:autospotting:group-id", id.toString())).build());
        when(lookup.forAccount(Provider.AWS, 7L)).thenReturn(List.of(row));
        Result r = discovery().discover(account, aws.view());
        assertThat(find(r, "ap-south-1/known-asg").matchedRule()).isEqualTo(1);
        assertThat(r.groups()).hasSize(1);
    }

    @Test
    void anExistingRowIsAnIdentityCheckOnly() {
        SpotGroup row = new SpotGroup();
        row.setId(UUID.randomUUID());
        row.setGroupKey("ap-south-1/web-asg");
        world(FakeAws.asg("web-asg").build());
        when(lookup.forAccount(Provider.AWS, 7L)).thenReturn(List.of(row));
        Result r = discovery().discover(account, aws.view());
        DiscoveredGroup g = find(r, "ap-south-1/web-asg");
        assertThat(g.matchedRule()).isEqualTo(1);
        assertThat(g.reconstructedSnapshot()).isNull();
        assertThat(g.providerStableId()).contains("autoScalingGroupName/web-asg");
    }

    @Test
    void aSpotShareWithoutAMarkerIsExternalSpotWithNoRow() {
        world(mixedWithSpotShare("ext-asg"));
        Result r = discovery().discover(account, aws.view());
        assertThat(r.groups()).isEmpty();
        assertThat(r.ruleCounts()).containsEntry(4, 1);
    }

    @Test
    void aTemplateWithSpotMarketOptionsIsExternalSpotToo() {
        world(FakeAws.asg("ext-lt-asg").build());
        doReturn(DescribeLaunchTemplateVersionsResponse.builder().launchTemplateVersions(LaunchTemplateVersion.builder()
                .versionNumber(1L).launchTemplateData(ResponseLaunchTemplateData.builder().imageId("ami-0aaa")
                        .instanceType("m5.large").instanceMarketOptions(LaunchTemplateInstanceMarketOptions.builder()
                                .marketType("spot").build()).build()).build()).build())
                .when(aws.ec2).describeLaunchTemplateVersions(any(DescribeLaunchTemplateVersionsRequest.class));
        Result r = discovery().discover(account, aws.view());
        assertThat(r.groups()).isEmpty();
        assertThat(r.ruleCounts()).containsEntry(4, 1);
    }

    @Test
    void anythingElseProducesNothingButIsCounted() {
        world(FakeAws.asg("plain-asg").build());
        Result r = discovery().discover(account, aws.view());
        assertThat(r.groups()).isEmpty();
        assertThat(r.ruleCounts()).containsEntry(5, 1);
    }

    @Test
    void namesAreNeverEvidence() {
        world(FakeAws.asg("spot-enabled-xamops-autospotting").build());
        assertThat(discovery().discover(account, aws.view()).groups()).isEmpty();
    }

    @Test
    void theLegacyTagWinsOverTheOtherRules() {
        world(mixedWithSpotShare("both-asg").toBuilder().tags(FakeAws.tag("spot-enabled", "true")).build());
        assertThat(find(discovery().discover(account, aws.view()), "ap-south-1/both-asg").matchedRule()).isEqualTo(2);
    }

    @Test
    void anUnreadableRegionIsReportedNeverTreatedAsNoGroups() {
        world(FakeAws.asg("legacy-asg").tags(FakeAws.tag("spot-enabled", "true")).build());
        doReturn(DescribeRegionsResponse.builder().regions(Region.builder().regionName("ap-south-1").build(),
                Region.builder().regionName("eu-west-1").build()).build()).when(aws.ec2).describeRegions();
        doThrow(SdkClientException.create("down")).when(broken.asg).describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class));
        Result r = discovery().discover(account, aws.view());
        assertThat(r.failedLocations()).containsExactly("eu-west-1");
        assertThat(r.groups()).hasSize(1);
    }

    @Test
    void ifRegionsCannotBeListedTheWholeAccountIsReportedFailed() {
        world();
        doThrow(SdkClientException.create("down")).when(aws.ec2).describeRegions();
        Result r = discovery().discover(account, aws.view());
        assertThat(r.groups()).isEmpty();
        assertThat(r.failedLocations()).containsExactly("regions");
    }

    @Test
    void pagesAreFollowedToTheEnd() {
        world(FakeAws.asg("a").build());
        doReturn(software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse.builder()
                .autoScalingGroups(FakeAws.asg("a").tags(FakeAws.tag("spot-enabled", "true")).build()).nextToken("t2").build(),
                software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse.builder()
                        .autoScalingGroups(FakeAws.asg("b").tags(FakeAws.tag("spot-enabled", "true")).build()).build())
                .when(aws.asg).describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class));
        assertThat(discovery().discover(account, aws.view()).groups()).extracting(DiscoveredGroup::groupKey)
                .containsExactlyInAnyOrder("ap-south-1/a", "ap-south-1/b");
    }
}
