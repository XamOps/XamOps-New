package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.WorkloadApplyBodyMapper.ContainerResources;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-field diff both callers share (design 3.4): the autonomous loop's {@code applyBody} adapter and the
 * approved executor. One table of cases is run through the mapper directly and through
 * {@code AutomaticApplyService.applyBody}, asserting identical output.
 */
class WorkloadApplyBodyMapperTest {

    private static ContainerResources c(String name, Integer cpuReq, Integer cpuLim, Long memReq, Long memLim) {
        return new ContainerResources(name, cpuReq, cpuLim, memReq, memLim);
    }

    private static ApplyRequestDto.Container out(String name, Integer cpuReq, Long memReq, Integer cpuLim, Long memLim) {
        return new ApplyRequestDto.Container(name, cpuReq, memReq, cpuLim, memLim);
    }

    @Test
    void onlyFieldsThatDifferAreEmittedAndUnchangedOnesAreNull() {
        var body = WorkloadApplyBodyMapper.diff(
                List.of(c("app", 500, null, 256L, 512L)),
                List.of(c("app", 220, null, 256L, 512L)));
        assertThat(body).containsExactly(out("app", 220, null, null, null));
    }

    @Test
    void aFrozenContainerWithNothingDifferentIsOmittedEntirely() {
        var body = WorkloadApplyBodyMapper.diff(
                List.of(c("app", 500, 500, 256L, 256L), c("sidecar", 10, 10, 16L, 16L)),
                List.of(c("app", 500, 500, 256L, 256L), c("sidecar", 20, 10, 16L, 16L)));
        assertThat(body).containsExactly(out("sidecar", 20, null, null, null));
    }

    @Test
    void aNullRecommendedFieldNeverClearsTheCurrentOne() {
        var body = WorkloadApplyBodyMapper.diff(
                List.of(c("app", 500, 800, 256L, 512L)),
                List.of(c("app", null, null, 128L, null)));
        assertThat(body).containsExactly(out("app", null, 128L, null, null));
    }

    @Test
    void outputFollowsTheRecommendedOrder() {
        var body = WorkloadApplyBodyMapper.diff(
                List.of(c("a", 1, null, null, null), c("b", 1, null, null, null)),
                List.of(c("b", 2, null, null, null), c("a", 2, null, null, null)));
        assertThat(body).extracting(ApplyRequestDto.Container::name).containsExactly("b", "a");
    }

    @Test
    void aContainerOnlyInRecommendedIsDiffedAgainstAllNullCurrent() {
        var body = WorkloadApplyBodyMapper.diff(List.of(),
                List.of(c("new", 100, 200, 64L, 128L)));
        assertThat(body).containsExactly(out("new", 100, 64L, 200, 128L));
    }

    @Test
    void aContainerOnlyInCurrentIsSkipped() {
        var body = WorkloadApplyBodyMapper.diff(List.of(c("gone", 1, 1, 1L, 1L)), List.of());
        assertThat(body).isEmpty();
    }

    @Test
    void aDuplicateContainerNameIsAProgrammingError() {
        assertThatThrownBy(() -> WorkloadApplyBodyMapper.diff(
                List.of(c("a", 1, 1, 1L, 1L), c("a", 2, 2, 2L, 2L)), List.of(c("a", 3, 3, 3L, 3L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkloadApplyBodyMapper.diff(
                List.of(c("a", 1, 1, 1L, 1L)), List.of(c("a", 3, 3, 3L, 3L), c("a", 4, 4, 4L, 4L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ both callers agree

    private static VerticalWorkloadDto.ContainerDto dto(String name, VerticalWorkloadDto.ResourcesDto cur,
                                                          VerticalWorkloadDto.ResourcesDto rec) {
        return new VerticalWorkloadDto.ContainerDto(name, null, cur, rec, null, null);
    }

    private static VerticalWorkloadDto.ResourcesDto res(Integer cpuReq, Long memReq, Integer cpuLim, Long memLim) {
        return new VerticalWorkloadDto.ResourcesDto(cpuReq, memReq, cpuLim, memLim);
    }

    @Test
    void theAutonomousAdapterAndTheMapperProduceIdenticalBodies() {
        // (name, current, recommended) rows, run through both entry points
        VerticalWorkloadDto.ResourcesDto cur = res(500, 256L, null, 512L);
        VerticalWorkloadDto.ResourcesDto rec = res(220, 256L, null, 512L);
        VerticalWorkloadDto.ResourcesDto frozenCur = res(100, 64L, 100, 64L);
        VerticalWorkloadDto workload = workloadOf(
                dto("app", cur, rec),
                dto("frozen", frozenCur, frozenCur),
                dto("norec", res(1, 1L, 1, 1L), null));

        List<ApplyRequestDto.Container> viaAdapter = AutomaticApplyService.applyBody(workload);
        List<ApplyRequestDto.Container> viaMapper = WorkloadApplyBodyMapper.diff(
                List.of(c("app", 500, null, 256L, 512L), c("frozen", 100, 100, 64L, 64L)),
                List.of(c("app", 220, null, 256L, 512L), c("frozen", 100, 100, 64L, 64L)));

        assertThat(viaAdapter).isEqualTo(viaMapper).containsExactly(out("app", 220, null, null, null));
    }

    @Test
    void aMissingCurrentEnvelopeMeansEveryRecommendedFieldIsEmitted() {
        VerticalWorkloadDto workload = workloadOf(dto("app", null, res(100, 64L, 200, 128L)));
        assertThat(AutomaticApplyService.applyBody(workload)).containsExactly(out("app", 100, 64L, 200, 128L));
    }

    private static VerticalWorkloadDto workloadOf(VerticalWorkloadDto.ContainerDto... containers) {
        return new VerticalWorkloadDto(new VerticalWorkloadDto.WorkloadRefDto("shop", "Deployment", "api"),
                null, null, null, null, null, null, List.of(containers), null, false, null, null, null);
    }
}
