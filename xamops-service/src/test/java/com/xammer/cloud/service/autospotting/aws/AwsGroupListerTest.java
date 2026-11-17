package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.autospotting.v2.GroupListDto;
import com.xammer.cloud.dto.autospotting.v2.GroupRowDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.EligibilityCache;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * plan-phase-12.md 12.5 (NFR-03, FR-OBS-06, AC-CMP-06): the list runs in LIST mode within the budget; a
 * region past it comes back in failedLocations with TIMEOUT and the response is partial; an unreadable
 * region is reported with its error kind, never "no groups". Mocked SDK clients only.
 */
class AwsGroupListerTest {

    private static final String PRODUCT = "{\"terms\":{\"OnDemand\":{\"S.J\":{\"priceDimensions\":{\"S.J.6\":"
            + "{\"pricePerUnit\":{\"USD\":\"0.0960000000\"}}}}}},\"version\":\"v\",\"publicationDate\":\"2026-11-01T00:00:00Z\"}";

    private final CloudAccount account = FakeAws.account();
    private final FakeAws mumbai = new FakeAws();
    private final FakeAws ireland = new FakeAws();
    private final FakeAws virginia = new FakeAws();
    private final PricingClient pricing = mock(PricingClient.class);

    private AwsGroupLister lister(Duration budget, AwsViewSource views) {
        when(pricing.getProducts(any(GetProductsRequest.class)))
                .thenReturn(GetProductsResponse.builder().priceList(PRODUCT).build());
        AwsEstimatePriceService prices = new AwsEstimatePriceService(pricing, views);
        AwsCommitmentUtilization commitments = mock(AwsCommitmentUtilization.class);
        when(commitments.thresholdPercent()).thenReturn(80.0);
        when(commitments.utilization(any())).thenReturn(Optional.empty());
        GroupLookup lookup = mock(GroupLookup.class);
        when(lookup.forAccount(any(), any())).thenReturn(List.of());
        AwsGroupStatusReader status = new AwsGroupStatusReader();
        AwsEligibilityEvaluator evaluator = new AwsEligibilityEvaluator(views, status, new AwsPlanReader(prices),
                mock(EligibilityCache.class), commitments, new AwsRegionCoverage() {
            @Override
            public boolean covered(CloudAccount a, String r) {
                return true;
            }
        }, lookup, new AutoSpottingMetrics(new SimpleMeterRegistry()), 12);
        AwsSpotProvider provider = new AwsSpotProvider(mock(CloudAccountRepository.class), views, evaluator, status,
                prices, mock(AutoSpottingSwitchProperties.class));
        return new AwsGroupLister(views, evaluator, provider, status, lookup, budget, Clock.systemUTC());
    }

    private void regions(String... names) {
        Region[] rs = new Region[names.length];
        for (int i = 0; i < names.length; i++) {
            rs[i] = Region.builder().regionName(names[i]).build();
        }
        doReturn(DescribeRegionsResponse.builder().regions(rs).build()).when(virginia.ec2).describeRegions();
    }

    @Test
    void listsEveryRegionInListModeWithNoMutatingCall() {
        mumbai.happyPath(FakeAws.asg("web-asg").build());
        ireland.happyPath(FakeAws.asg("api-asg").healthCheckType("EC2").targetGroupARNs("arn:tg").build());
        virginia.happyPath();
        regions("ap-south-1", "eu-west-1");
        AwsGroupLister lister = lister(Duration.ofSeconds(10), (a, region) -> switch (region) {
            case "ap-south-1" -> mumbai.view();
            case "eu-west-1" -> ireland.view();
            default -> virginia.view();
        });

        GroupListDto list = lister.list(account);

        assertThat(list.partial()).isFalse();
        assertThat(list.failedLocations()).isEmpty();
        assertThat(list.items()).extracting(GroupRowDto::groupKey).containsExactly("ap-south-1/web-asg", "eu-west-1/api-asg");
        GroupRowDto web = list.items().get(0);
        assertThat(web.managed()).isFalse();
        assertThat(web.state()).isEqualTo("UNMANAGED");
        assertThat(web.enableAllowed()).isTrue();
        assertThat(web.deferred()).isNotEmpty(); // LIST defers the expensive checks: "Checked at plan"
        GroupRowDto api = list.items().get(1);
        assertThat(api.enableAllowed()).isFalse();
        assertThat(api.enableBlockedBy()).isEqualTo("EC2_HEALTH_WITH_LB");
        assertThat(list.blockedCounts()).containsEntry("EC2_HEALTH_WITH_LB", 1);
        assertThat(list.kpis().groups()).isEqualTo(2);
        assertThat(list.kpis().eligible()).isEqualTo(1);
        assertThat(list.kpis().blocked()).isEqualTo(1);
        mumbai.assertReadOnly();
        ireland.assertReadOnly();
        virginia.assertReadOnly();
        // LIST made no per-group expensive call.
        assertThat(mumbai.calls("simulatePrincipalPolicy")).isZero();
        assertThat(mumbai.calls("getServiceQuota")).isZero();
    }

    @Test
    void aRegionPastTheBudgetIsATimeoutAndTheResponseIsPartial() {
        mumbai.happyPath(FakeAws.asg("web-asg").build());
        virginia.happyPath();
        regions("ap-south-1", "eu-west-1");
        AwsGroupLister lister = lister(Duration.ofMillis(400), (a, region) -> {
            if ("eu-west-1".equals(region)) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ireland.view();
            }
            return "ap-south-1".equals(region) ? mumbai.view() : virginia.view();
        });

        GroupListDto list = lister.list(account);

        assertThat(list.partial()).isTrue();
        assertThat(list.failedLocations()).extracting(GroupListDto.FailedLocation::location, GroupListDto.FailedLocation::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("eu-west-1", "TIMEOUT"));
        assertThat(list.items()).extracting(GroupRowDto::groupKey).containsExactly("ap-south-1/web-asg");
    }

    @Test
    void anUnreadableRegionIsReportedWithItsKindNeverAsNoGroups() {
        mumbai.happyPath(FakeAws.asg("web-asg").build());
        ireland.happyPath();
        virginia.happyPath();
        regions("ap-south-1", "eu-west-1");
        doThrow(AutoScalingException.builder().statusCode(403).build()).when(ireland.asg)
                .describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class));
        AwsGroupLister lister = lister(Duration.ofSeconds(10), (a, region) -> switch (region) {
            case "ap-south-1" -> mumbai.view();
            case "eu-west-1" -> ireland.view();
            default -> virginia.view();
        });
        GroupListDto list = lister.list(account);
        assertThat(list.partial()).isTrue();
        assertThat(list.failedLocations()).extracting(GroupListDto.FailedLocation::code).containsExactly("PERMISSION");
        assertThat(list.items()).hasSize(1);
    }

    @Test
    void ifRegionsCannotBeListedTheListIsPartialNotEmptySuccess() {
        virginia.happyPath();
        doThrow(AwsServiceException.builder().statusCode(403).build()).when(virginia.ec2).describeRegions();
        AwsGroupLister lister = lister(Duration.ofSeconds(5), (a, region) -> virginia.view());
        GroupListDto list = lister.list(account);
        assertThat(list.items()).isEmpty();
        assertThat(list.partial()).isTrue();
        assertThat(list.failedLocations()).extracting(GroupListDto.FailedLocation::location).containsExactly("regions");
    }

    @Test
    void anUnpricedGroupIsNotPricedAndNeverZeroAndTheKpiDoesNotHideIt() {
        mumbai.happyPath(FakeAws.asg("web-asg").build());
        virginia.happyPath();
        regions("ap-south-1");
        AwsGroupLister lister = lister(Duration.ofSeconds(10), (a, region) ->
                "ap-south-1".equals(region) ? mumbai.view() : virginia.view());
        when(pricing.getProducts(any(GetProductsRequest.class)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("down"));
        GroupListDto list = lister.list(account);
        GroupRowDto row = list.items().get(0);
        assertThat(row.savingsUnknown()).isTrue();
        assertThat(row.monthlySavings()).isNull();
        assertThat(row.savingsLabel()).isEqualTo("Not priced");
        assertThat(list.kpis().potentialMonthlySavings()).isNull();
        assertThat(list.kpis().savingsUnknownGroups()).isEqualTo(1);
    }

    @Test
    void aPricedGroupCarriesAnEstimate() {
        mumbai.happyPath(FakeAws.asg("web-asg").build());
        virginia.happyPath();
        regions("ap-south-1");
        AwsGroupLister lister = lister(Duration.ofSeconds(10), (a, region) ->
                "ap-south-1".equals(region) ? mumbai.view() : virginia.view());
        GroupRowDto row = lister.list(account).items().get(0);
        assertThat(row.savingsUnknown()).isFalse();
        assertThat(row.monthlySavings()).isEqualTo(48.18);
        assertThat(row.savingsLabel()).isEqualTo("Estimate");
    }
}
