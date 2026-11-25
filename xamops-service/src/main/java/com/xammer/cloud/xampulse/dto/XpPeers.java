package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * A host compared with its peers (same Auto Scaling group, else same instance type and name pattern) over a range.
 * {@code basis}: ASG, TYPE_NAME or NONE. Each metric: the host's value, the peers' median and range, a robust z
 * (deviation over the peers' scaled MAD) and a verdict: HIGHER / LOWER (an outlier), IN_LINE, NO_DATA (the host has no
 * value) or TOO_FEW (fewer than two peers have one). With two peers a verdict is marked {@code indicative}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpPeers(String instanceId, String basis, String basisLabel, List<Peer> peers, List<Metric> metrics, String note) {

    public record Peer(String instanceId, String name) {}

    public record Metric(String key, String label, String unit, Double value, Double peerMedian, Double peerMin, Double peerMax,
                         Double z, String verdict, boolean indicative, Map<String, Double> peerValues) {}
}
