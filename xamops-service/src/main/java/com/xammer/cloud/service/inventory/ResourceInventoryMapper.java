package com.xammer.cloud.service.inventory;

import com.xammer.cloud.dto.DashboardData;

import java.util.Map;

/**
 * Turns display-type counts into the dashboard's Resource Inventory widget.
 *
 * <p>
 * Deliberately a static utility rather than a method on a service. Three callers need this
 * mapping — the describe path in {@code DashboardDataService}, the inventory plane, and the
 * WebSocket push that keeps an open dashboard current — and all three must produce identical
 * numbers from identical inputs. A private copy in each would drift the first time a type was
 * added to one and not the others, with no error anywhere: just a widget quietly disagreeing
 * with the cloud list, which is the bug this class was extracted to prevent.
 *
 * <p>
 * Being static also means the describe path does not acquire a dependency on the inventory
 * plane's beans merely to format numbers it already has.
 *
 * <p>
 * Keys are the exact display strings {@code AwsResourceTypeMapper} emits and
 * {@code CloudListService} constructs. Changing one without changing the other silently zeroes
 * a row on the dashboard.
 */
public final class ResourceInventoryMapper {

    private ResourceInventoryMapper() {
    }

    public static DashboardData.ResourceInventory fromCounts(Map<String, Integer> counts) {
        DashboardData.ResourceInventory inventory = new DashboardData.ResourceInventory();

        inventory.setVpc(counts.getOrDefault("VPC", 0));
        inventory.setEcs(counts.getOrDefault("ECS Cluster", 0));
        inventory.setEc2(counts.getOrDefault("EC2 Instance", 0));
        inventory.setKubernetes(counts.getOrDefault("EKS Cluster", 0));
        inventory.setLambdas(counts.getOrDefault("Lambda Function", 0));
        inventory.setEbsVolumes(counts.getOrDefault("EBS Volume", 0));
        inventory.setImages(counts.getOrDefault("AMI", 0));
        // "EBS Snapshot", not "Snapshot": that is the string both the describe path and the
        // plane emit, so the old key never matched and this count was always zero.
        inventory.setSnapshots(counts.getOrDefault("EBS Snapshot", 0));
        inventory.setS3Buckets(counts.getOrDefault("S3 Bucket", 0));
        inventory.setRdsInstances(counts.getOrDefault("RDS Instance", 0));
        inventory.setRoute53Zones(counts.getOrDefault("Route 53 Zone", 0));
        inventory.setLoadBalancers(counts.getOrDefault("Load Balancer", 0));
        inventory.setLightsail(counts.getOrDefault("Lightsail Instance", 0));
        inventory.setAmplify(counts.getOrDefault("Amplify App", 0));

        // The frontend reads the *Count aliases; the fields above exist for older consumers.
        // Both are populated so neither shape has to be migrated.
        inventory.setEc2Count(inventory.getEc2());
        inventory.setS3Count(inventory.getS3Buckets());
        inventory.setRdsCount(inventory.getRdsInstances());
        inventory.setVpcCount(inventory.getVpc());
        inventory.setLambdaCount(inventory.getLambdas());
        inventory.setEbsCount(inventory.getEbsVolumes());
        inventory.setRoute53Count(inventory.getRoute53Zones());
        inventory.setLoadBalancerCount(inventory.getLoadBalancers());
        inventory.setEcsCount(inventory.getEcs());
        inventory.setEksCount(inventory.getKubernetes());
        inventory.setLightsailCount(inventory.getLightsail());
        inventory.setAmplifyCount(inventory.getAmplify());

        return inventory;
    }

    /** Convenience for the plane, whose counts arrive as longs from a SQL COUNT. */
    public static DashboardData.ResourceInventory fromLongCounts(Map<String, Long> counts) {
        Map<String, Integer> ints = new java.util.HashMap<>();
        counts.forEach((type, count) -> ints.put(type, count == null ? 0 : count.intValue()));
        return fromCounts(ints);
    }
}
