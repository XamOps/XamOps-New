package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.api.VerticalApplyService.ApplyRefusedException;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A check {@link VerticalApplyService#apply} runs against the live workload, after the workload is
 * read and the native-VPA guard has passed, and before re-validation or any patch.
 *
 * <p>Manual mode uses {@link #currentMatches}: an approved proposal carries the {@code params.current}
 * resource set the human reviewed, and if the live set differs the apply is refused
 * {@code PROPOSAL_STALE} with nothing sent to the cluster. The live value is read through
 * {@link VerticalDecisionMapper#resources}, the identical mapping (CPU integer millicores, memory whole
 * MiB rounded up) the proposer used to build {@code params.current}, so a memory value that is not a
 * whole MiB cannot make a proposal look stale to itself.
 */
@FunctionalInterface
public interface PreApplyCheck {

    /** Stable refusal code the executor maps to {@code STALE}. */
    String STALE_CODE = "PROPOSAL_STALE";

    /** @throws ApplyRefusedException {@code 409 PROPOSAL_STALE} when the live workload no longer matches */
    void verify(ClusterWorkloadInventory.Workload live);

    /** One container's resources in the units the apply API and the proposal params use. */
    record Expected(String container, Integer cpuRequestM, Integer cpuLimitM, Long memRequestMi, Long memLimitMi) {
    }

    /** The live current resource set of every container must equal {@code expected}, by container name. */
    static PreApplyCheck currentMatches(List<Expected> expected) {
        return live -> {
            Map<String, Expected> byName = new HashMap<>();
            for (Expected e : expected) {
                byName.put(e.container(), e);
            }
            if (live.containers().size() != byName.size()) {
                throw stale("the set of containers changed");
            }
            for (ClusterWorkloadInventory.ContainerSpec spec : live.containers()) {
                Expected want = byName.get(spec.name());
                if (want == null) {
                    throw stale("container " + spec.name() + " was not part of the proposal");
                }
                VerticalWorkloadDto.ResourcesDto have = VerticalDecisionMapper.resources(spec.current());
                if (have == null
                        || !Objects.equals(have.cpuRequestM(), want.cpuRequestM())
                        || !Objects.equals(have.cpuLimitM(), want.cpuLimitM())
                        || !Objects.equals(have.memRequestMi(), want.memRequestMi())
                        || !Objects.equals(have.memLimitMi(), want.memLimitMi())) {
                    throw stale("container " + spec.name() + " no longer has the resources that were reviewed");
                }
            }
        };
    }

    private static ApplyRefusedException stale(String why) {
        return new ApplyRefusedException(HttpStatus.CONFLICT, STALE_CODE, null,
                "This proposal is out of date: " + why + ". Nothing was changed.");
    }
}
