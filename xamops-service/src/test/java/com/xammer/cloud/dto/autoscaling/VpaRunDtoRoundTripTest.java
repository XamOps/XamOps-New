package com.xammer.cloud.dto.autoscaling;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.autoscaling.VpaRunDto.VpaWorkloadRun;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips a Go-emitted {@code VpaRun} JSON (dataType {@code autoscaling-vpa-runs}) through the
 * backend {@link VpaRunDto}, proving the two shapes match ({@code xamops-autoscaler/internal/
 * controller/vpa_manager.go}, {@code schemaVersion = 1}). The Go emitter omits no field and marshals
 * empty lists as {@code []}; the backend mapper is the lenient one the services use
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES = false}), so an added Go field never breaks the read.
 */
class VpaRunDtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A live run that resized one container, refused a DB pod, capped one, and reverted one. */
    private static final String GO_RUN_JSON = """
            {
              "schemaVersion": 1,
              "runId": "vpa-1793000000000000000",
              "generatedAt": "2026-10-25T00:05:00Z",
              "mode": "live",
              "dryRun": false,
              "summary": {
                "considered": 3,
                "resized": 1,
                "simulated": 0,
                "skipped": 1,
                "refused": 1,
                "reverted": 1,
                "failed": 0
              },
              "workloads": [
                {
                  "namespace": "shop",
                  "workloadName": "web",
                  "workloadKind": "Deployment",
                  "containerName": "app",
                  "status": "resized",
                  "reason": "in-place resize applied (restart-free)"
                },
                {
                  "namespace": "data",
                  "workloadName": "pg",
                  "workloadKind": "StatefulSet",
                  "containerName": "postgres",
                  "status": "refused",
                  "reason": "refused: db-primary"
                },
                {
                  "namespace": "shop",
                  "workloadName": "worker",
                  "workloadKind": "Deployment",
                  "containerName": "app",
                  "status": "skipped",
                  "reason": "skipped: vpa resize cap reached"
                },
                {
                  "namespace": "shop",
                  "workloadName": "web-abc123",
                  "workloadKind": "",
                  "containerName": "",
                  "status": "reverted",
                  "reason": "aborted: reverted after regression (OOMKilled)"
                }
              ]
            }
            """;

    @Test
    void roundTripsAGoEmittedRun() throws Exception {
        VpaRunDto run = mapper.readValue(GO_RUN_JSON, VpaRunDto.class);

        assertThat(run.schemaVersion()).isEqualTo(1);
        assertThat(run.runId()).isEqualTo("vpa-1793000000000000000");
        assertThat(run.generatedAt()).isEqualTo("2026-10-25T00:05:00Z");
        assertThat(run.mode()).isEqualTo("live");
        assertThat(run.dryRun()).isFalse();

        VpaRunDto.VpaRunSummary s = run.summary();
        assertThat(s.considered()).isEqualTo(3);
        assertThat(s.resized()).isEqualTo(1);
        assertThat(s.simulated()).isZero();
        assertThat(s.skipped()).isEqualTo(1);
        assertThat(s.refused()).isEqualTo(1);
        assertThat(s.reverted()).isEqualTo(1);
        assertThat(s.failed()).isZero();

        assertThat(run.workloads()).hasSize(4);
        VpaWorkloadRun resized = run.workloads().get(0);
        assertThat(resized.namespace()).isEqualTo("shop");
        assertThat(resized.workloadName()).isEqualTo("web");
        assertThat(resized.workloadKind()).isEqualTo("Deployment");
        assertThat(resized.containerName()).isEqualTo("app");
        assertThat(resized.status()).isEqualTo(VpaRunDto.STATUS_RESIZED);

        VpaWorkloadRun refused = run.workloads().get(1);
        assertThat(refused.status()).isEqualTo(VpaRunDto.STATUS_REFUSED);
        assertThat(refused.reason()).isEqualTo("refused: db-primary");

        VpaWorkloadRun skipped = run.workloads().get(2);
        assertThat(skipped.status()).isEqualTo(VpaRunDto.STATUS_SKIPPED);
        assertThat(skipped.reason()).isEqualTo("skipped: vpa resize cap reached");

        // A reverted row carries the pod name as workloadName and leaves kind/container empty.
        VpaWorkloadRun reverted = run.workloads().get(3);
        assertThat(reverted.status()).isEqualTo(VpaRunDto.STATUS_REVERTED);
        assertThat(reverted.workloadName()).isEqualTo("web-abc123");
        assertThat(reverted.workloadKind()).as("reverted row: empty kind").isEmpty();
        assertThat(reverted.containerName()).as("reverted row: empty container").isEmpty();
    }

    @Test
    void roundTripsASimulateRunWithNoWorkloads() throws Exception {
        String json = """
                {
                  "schemaVersion": 1,
                  "runId": "vpa-1793000000000000100",
                  "generatedAt": "2026-10-25T00:10:00Z",
                  "mode": "simulate",
                  "dryRun": true,
                  "summary": {"considered": 0, "resized": 0, "simulated": 0, "skipped": 0,
                              "refused": 0, "reverted": 0, "failed": 0},
                  "workloads": []
                }
                """;

        VpaRunDto run = mapper.readValue(json, VpaRunDto.class);

        assertThat(run.mode()).isEqualTo("simulate");
        assertThat(run.dryRun()).isTrue();
        assertThat(run.workloads()).isEmpty();
        assertThat(run.summary().considered()).isZero();
    }

    @Test
    void anUnknownFutureFieldIsIgnored() throws Exception {
        String json = """
                {
                  "schemaVersion": 1,
                  "runId": "vpa-1793000000000000200",
                  "generatedAt": "2026-10-25T00:15:00Z",
                  "mode": "live",
                  "dryRun": false,
                  "summary": {"considered": 0, "resized": 0, "simulated": 0, "skipped": 0,
                              "refused": 0, "reverted": 0, "failed": 0},
                  "workloads": [],
                  "futureField": {"x": 1}
                }
                """;

        VpaRunDto run = mapper.readValue(json, VpaRunDto.class);

        assertThat(run.runId()).isEqualTo("vpa-1793000000000000200");
        assertThat(run.workloads()).isEmpty();
    }
}
