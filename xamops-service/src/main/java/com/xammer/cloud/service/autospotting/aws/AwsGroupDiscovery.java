package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.AsgSpotConfigRepository;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.core.discovery.DiscoveredGroup;
import com.xammer.cloud.service.autospotting.spi.GroupDiscovery;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * AWS discovery (design-core.md §2.5, AC-SPN-12): rules 1-5 over every enabled region, read only.
 * First matching rule wins:
 *
 * <ol>
 *   <li>a {@code spot_group} row exists for the key: identity check only;</li>
 *   <li>tag {@code spot-enabled=true} (the legacy marker, A-03): LEGACY_UNCONFIRMED with a
 *       reconstructed snapshot, {@code exact=false};</li>
 *   <li>tag {@code xamops:autospotting:group-id=<uuid>} with no row for that uuid:
 *       LEGACY_UNCONFIRMED, reason {@code ORPHAN_MARKER}, never managed automatically;</li>
 *   <li>no XamOps marker and a Spot share (a mixed instances policy below 100% On-Demand, or a launch
 *       template market type {@code spot}): {@code EXTERNAL_SPOT}, a read-time class with no row;</li>
 *   <li>anything else: nothing.</li>
 * </ol>
 * Names are never evidence. A region that cannot be read is reported in {@code failedLocations},
 * never treated as "no groups" (FR-OBS-06). No mutating call is made: the view is read only.
 */
@Component
public class AwsGroupDiscovery implements GroupDiscovery {

    static final int MAX_PAGES = 50;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AwsViewSource views;
    private final GroupLookup groupLookup;
    private final AsgSpotConfigRepository legacyConfigs;
    private final AwsPlanReader reader;

    public AwsGroupDiscovery(AwsViewSource views, GroupLookup groupLookup, AsgSpotConfigRepository legacyConfigs,
                             AwsPlanReader reader) {
        this.views = views;
        this.groupLookup = groupLookup;
        this.legacyConfigs = legacyConfigs;
        this.reader = reader;
    }

    @Override
    public Provider provider() {
        return Provider.AWS;
    }

    @Override
    public DiscoveredGroup.Result discover(CloudAccount account, ReadOnlyCloudView ignoredMarkerView) {
        List<DiscoveredGroup> groups = new ArrayList<>();
        Map<Integer, Integer> counts = new TreeMap<>();
        List<String> failed = new ArrayList<>();

        Map<String, SpotGroup> rows = new HashMap<>();
        Set<String> rowIds = new HashSet<>();
        for (SpotGroup row : groupLookup.forAccount(Provider.AWS, account.getId())) {
            rows.put(row.getGroupKey(), row);
            rowIds.add(String.valueOf(row.getId()));
        }

        List<String> regions;
        try {
            regions = AwsRegions.enabled(views, account);
        } catch (RuntimeException e) {
            return new DiscoveredGroup.Result(Provider.AWS, List.of(), counts, List.of("regions"));
        }
        for (String region : regions) {
            List<AutoScalingGroup> described;
            AwsReadOnlyView view;
            try {
                view = views.view(account, region);
                described = describeAll(view);
            } catch (RuntimeException e) {
                failed.add(region);
                continue;
            }
            for (AutoScalingGroup raw : described) {
                classify(account, view, region, raw, rows, rowIds, groups, counts);
            }
        }
        return new DiscoveredGroup.Result(Provider.AWS, groups, counts, failed);
    }

    private void classify(CloudAccount account, AwsReadOnlyView view, String region, AutoScalingGroup raw,
                          Map<String, SpotGroup> rows, Set<String> rowIds, List<DiscoveredGroup> out,
                          Map<Integer, Integer> counts) {
        AwsGroupFacts g = AwsGroupFacts.from(region, raw);
        String key = g.groupKey();
        // 1. An existing row: identity check only.
        if (rows.containsKey(key)) {
            out.add(new DiscoveredGroup(key, g.arn(), 1, null, null, null, g.hasMarker(), g.hasSpotShare()));
            counts.merge(1, 1, Integer::sum);
            return;
        }
        // 2. The legacy marker tag.
        if (g.hasLegacyTag()) {
            out.add(new DiscoveredGroup(key, g.arn(), 2, null,
                    AwsSnapshotCodec.encode(region, raw, AwsSnapshotCodec.Context.none(), true),
                    legacyDesiredConfig(account, region, g.name()), g.hasMarker(), g.hasSpotShare()));
            counts.merge(2, 1, Integer::sum);
            return;
        }
        // 3. A XamOps marker whose group id has no row.
        if (g.hasMarker() && !rowIds.contains(g.markerGroupId())) {
            out.add(new DiscoveredGroup(key, g.arn(), 3, DiscoveredGroup.ORPHAN_MARKER,
                    AwsSnapshotCodec.encode(region, raw, AwsSnapshotCodec.Context.none(), true), null,
                    true, g.hasSpotShare()));
            counts.merge(3, 1, Integer::sum);
            return;
        }
        // 4. No XamOps marker and a Spot share: a read-time class, no row.
        if (!g.hasMarker() && (g.hasSpotShare() || templateRunsSpot(account, view, g))) {
            counts.merge(4, 1, Integer::sum);
            return;
        }
        // 5. Anything else: nothing.
        counts.merge(5, 1, Integer::sum);
    }

    private boolean templateRunsSpot(CloudAccount account, AwsReadOnlyView view, AwsGroupFacts g) {
        if (g.mixedInstancesPolicy() || g.launchTemplate() == null) {
            return false;
        }
        try {
            AwsLaunchInfo info = reader.launch(account, view, g.launchTemplate());
            return info.marketOptionsSet() && "spot".equalsIgnoreCase(info.marketType());
        } catch (RuntimeException e) {
            return false; // unreadable template: no row either way
        }
    }

    private String legacyDesiredConfig(CloudAccount account, String region, String name) {
        return legacyConfigs.findByAccountIdAndRegionAndAsgName(account.getId(), region, name).map(c -> {
            ObjectNode n = JSON.createObjectNode();
            n.put("spotAllocationStrategy", c.getSpotAllocationStrategy());
            if (c.getSpotPercentage() != null) {
                n.put("spotPercentage", c.getSpotPercentage());
            }
            if (c.getOnDemandCount() != null) {
                n.put("onDemandCount", c.getOnDemandCount());
            }
            if (c.getAllowedInstanceTypes() != null) {
                n.put("allowedInstanceTypes", c.getAllowedInstanceTypes());
            }
            try {
                return JSON.writeValueAsString(n);
            } catch (JsonProcessingException e) {
                return null;
            }
        }).orElse(null);
    }

    /** Every group in the region (paginated; a failure on any page fails the whole region). */
    static List<AutoScalingGroup> describeAll(AwsReadOnlyView view) {
        List<AutoScalingGroup> out = new ArrayList<>();
        String token = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            final String next = token;
            DescribeAutoScalingGroupsResponse resp = AwsErrors.read("DescribeAutoScalingGroups", () ->
                    view.autoScaling().describeAutoScalingGroups(
                            DescribeAutoScalingGroupsRequest.builder().nextToken(next).build()));
            out.addAll(resp.autoScalingGroups());
            token = resp.nextToken();
            if (token == null || token.isEmpty()) {
                return out;
            }
        }
        throw new AwsReadException("DescribeAutoScalingGroups", com.xammer.cloud.service.autospotting.spi.ErrorKind.TRANSIENT);
    }
}
