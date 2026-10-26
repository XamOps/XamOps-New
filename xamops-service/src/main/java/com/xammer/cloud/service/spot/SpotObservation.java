package com.xammer.cloud.service.spot;

import java.time.Instant;

/**
 * One producer's sighting of a Spot node lifecycle, merged into its row by
 * {@link SpotInterruptionWriter#upsert} (design §B4.2). Every field a producer did not report is
 * null; the merge fills nulls and never overwrites a known value with one.
 *
 * @param key            the row key
 * @param instanceId     EC2 instance id, when reported
 * @param instanceType   node.kubernetes.io/instance-type
 * @param zone           topology.kubernetes.io/zone
 * @param capacityType   spot | on-demand
 * @param nodegroup      managed nodegroup or Karpenter nodepool
 * @param kind           interruption | rebalance | unconfirmed
 * @param source         nth-taint | nth-label-legacy | karpenter-event | node-removed
 * @param noticedAt      when this observation says the notice (or removal) was seen
 * @param handling       handling at notice
 * @param nodeLastSeenAt last snapshot that listed the node, when this observation knows it
 * @param nodeRemovedAt  first snapshot without the node (removal observations only)
 * @param cordoned       {@code spec.unschedulable} before removal, when this observation knows it
 */
public record SpotObservation(SpotKey key,
                              String instanceId,
                              String instanceType,
                              String zone,
                              String capacityType,
                              String nodegroup,
                              String kind,
                              String source,
                              Instant noticedAt,
                              SpotHandling handling,
                              Instant nodeLastSeenAt,
                              Instant nodeRemovedAt,
                              Boolean cordoned) {

    /** The row key: one node lifecycle in one cluster. */
    public record SpotKey(Long clusterConfigId, String nodeName, Instant nodeCreatedAt) {
    }
}
