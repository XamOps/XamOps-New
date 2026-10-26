package com.xammer.cloud.dto.autoscaling;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanDto.MoveType;
import com.xammer.cloud.dto.autoscaling.ConsolidationRunDto.ConsolidationNodeRun;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips a Go-emitted {@code ConsolidationRunDto} JSON (dataType
 * {@code autoscaling-consolidation-runs}) through the backend record, proving the two shapes match
 * ({@code xamops-autoscaler/internal/consolidation/run.go}, {@code RunSchemaVersion = 1}). The Go
 * emitter omits no field and marshals empty lists as {@code []}; the backend mapper is the lenient
 * one the services use ({@code FAIL_ON_UNKNOWN_PROPERTIES = false}), so an added Go field never
 * breaks the read.
 */
class ConsolidationRunContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A live run that removed one node and deferred another, exactly as the Go agent emits it. */
    private static final String GO_RUN_JSON = """
            {
              "schemaVersion": 1,
              "runId": "consolidation-1793000000",
              "generatedAt": "2026-10-25T00:05:00Z",
              "mode": "live",
              "dryRun": false,
              "actor": {
                "kind": "KARPENTER",
                "evidence": ["karpenter.sh/provisioner-name"],
                "note": "Display only."
              },
              "summary": {
                "considered": 3,
                "removed": 1,
                "deferred": 1,
                "simulated": 0,
                "skipped": 1,
                "refused": 0,
                "aborted": 0,
                "podsEvicted": 7
              },
              "nodes": [
                {
                  "node": {
                    "name": "ip-10-0-1-10",
                    "nodeGroup": "ng-a",
                    "instanceType": "m5.large",
                    "capacityType": "ON_DEMAND",
                    "zone": "ap-south-1a",
                    "fargate": false,
                    "providerId": "aws:///ap-south-1a/i-0abc123",
                    "cpuRequestedPct": 12.3,
                    "memRequestedPct": 8.1
                  },
                  "moveType": "DRAIN",
                  "status": "removed",
                  "decision": "xamops-asg",
                  "reason": "drained and terminated via XamOps ASG",
                  "podsEvicted": 7
                },
                {
                  "node": {
                    "name": "ip-10-0-1-11",
                    "nodeGroup": "ng-b",
                    "instanceType": "m5.xlarge",
                    "capacityType": "spot",
                    "zone": "ap-south-1b",
                    "fargate": false,
                    "providerId": "aws:///ap-south-1b/i-0def456",
                    "cpuRequestedPct": 0.0,
                    "memRequestedPct": 0.0
                  },
                  "moveType": "EMPTY",
                  "status": "deferred",
                  "decision": "defer-to-actor",
                  "reason": "cordoned and drained; left for Karpenter",
                  "podsEvicted": 0
                },
                {
                  "node": {
                    "name": "ip-10-0-1-12",
                    "nodeGroup": "ng-a",
                    "instanceType": "m5.large",
                    "capacityType": "ON_DEMAND",
                    "zone": "ap-south-1a",
                    "fargate": false,
                    "providerId": "aws:///ap-south-1a/i-0ghi789",
                    "cpuRequestedPct": 30.0,
                    "memRequestedPct": 22.0
                  },
                  "moveType": "DRAIN",
                  "status": "skipped",
                  "decision": "",
                  "reason": "within consolidate-after dwell",
                  "podsEvicted": 0
                }
              ]
            }
            """;

    @Test
    void roundTripsAGoEmittedRun() throws Exception {
        ConsolidationRunDto run = mapper.readValue(GO_RUN_JSON, ConsolidationRunDto.class);

        assertThat(run.schemaVersion()).isEqualTo(1);
        assertThat(run.runId()).isEqualTo("consolidation-1793000000");
        assertThat(run.generatedAt()).isEqualTo("2026-10-25T00:05:00Z");
        assertThat(run.mode()).isEqualTo("live");
        assertThat(run.dryRun()).isFalse();

        assertThat(run.actor().kind()).isEqualTo("KARPENTER");
        assertThat(run.actor().evidence()).containsExactly("karpenter.sh/provisioner-name");
        assertThat(run.actor().note()).isEqualTo("Display only.");

        ConsolidationRunDto.RunSummary s = run.summary();
        assertThat(s.considered()).isEqualTo(3);
        assertThat(s.removed()).isEqualTo(1);
        assertThat(s.deferred()).isEqualTo(1);
        assertThat(s.simulated()).isZero();
        assertThat(s.skipped()).isEqualTo(1);
        assertThat(s.refused()).isZero();
        assertThat(s.aborted()).isZero();
        assertThat(s.podsEvicted()).isEqualTo(7);

        assertThat(run.nodes()).hasSize(3);
        ConsolidationNodeRun removed = run.nodes().get(0);
        assertThat(removed.node().name()).isEqualTo("ip-10-0-1-10");
        assertThat(removed.node().providerId()).isEqualTo("aws:///ap-south-1a/i-0abc123");
        assertThat(removed.node().capacityType()).isEqualTo("ON_DEMAND");
        assertThat(removed.node().cpuRequestedPct()).isEqualTo(12.3);
        assertThat(removed.moveType()).isEqualTo(MoveType.DRAIN);
        assertThat(removed.status()).isEqualTo(ConsolidationRunDto.STATUS_REMOVED);
        assertThat(removed.decision()).isEqualTo(ConsolidationRunDto.DECISION_XAMOPS_ASG);
        assertThat(removed.podsEvicted()).isEqualTo(7);

        ConsolidationNodeRun deferred = run.nodes().get(1);
        assertThat(deferred.moveType()).isEqualTo(MoveType.EMPTY);
        assertThat(deferred.status()).isEqualTo(ConsolidationRunDto.STATUS_DEFERRED);
        assertThat(deferred.decision()).isEqualTo(ConsolidationRunDto.DECISION_DEFER_TO_ACTOR);

        ConsolidationNodeRun skipped = run.nodes().get(2);
        assertThat(skipped.status()).isEqualTo(ConsolidationRunDto.STATUS_SKIPPED);
        assertThat(skipped.decision()).as("skipped-before-drain carries an empty decision").isEmpty();
    }

    @Test
    void roundTripsASimulateRunWithNoNodes() throws Exception {
        String json = """
                {
                  "schemaVersion": 1,
                  "runId": "consolidation-1793000100",
                  "generatedAt": "2026-10-25T00:10:00Z",
                  "mode": "simulate",
                  "dryRun": true,
                  "actor": {"kind": "NONE", "evidence": [], "note": "Display only."},
                  "summary": {"considered": 0, "removed": 0, "deferred": 0, "simulated": 0,
                              "skipped": 0, "refused": 0, "aborted": 0, "podsEvicted": 0},
                  "nodes": []
                }
                """;

        ConsolidationRunDto run = mapper.readValue(json, ConsolidationRunDto.class);

        assertThat(run.mode()).isEqualTo("simulate");
        assertThat(run.dryRun()).isTrue();
        assertThat(run.actor().evidence()).isEmpty();
        assertThat(run.nodes()).isEmpty();
        assertThat(run.summary().considered()).isZero();
    }

    @Test
    void anUnknownFutureFieldIsIgnored() throws Exception {
        String json = """
                {
                  "schemaVersion": 1,
                  "runId": "consolidation-1793000200",
                  "generatedAt": "2026-10-25T00:15:00Z",
                  "mode": "live",
                  "dryRun": false,
                  "actor": {"kind": "NONE", "evidence": [], "note": ""},
                  "summary": {"considered": 0, "removed": 0, "deferred": 0, "simulated": 0,
                              "skipped": 0, "refused": 0, "aborted": 0, "podsEvicted": 0},
                  "nodes": [],
                  "futureField": {"x": 1}
                }
                """;

        ConsolidationRunDto run = mapper.readValue(json, ConsolidationRunDto.class);

        assertThat(run.runId()).isEqualTo("consolidation-1793000200");
        assertThat(run.nodes()).isEmpty();
    }
}
