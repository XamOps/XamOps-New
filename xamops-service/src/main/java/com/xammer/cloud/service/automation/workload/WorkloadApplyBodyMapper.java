package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one per-field diff that turns a decision's complete {@code recommended} envelope into an apply
 * body (extracted from {@code AutomaticApplyService.applyBody}). The autonomous loop and the approved
 * executor both call it, so a proposal executes exactly the fields the autonomous path would have
 * patched, and never a resource the engine deliberately froze.
 *
 * <p>Rules: output order follows {@code recommended}; a container present in {@code recommended} and
 * absent from {@code current} is diffed against all-null current fields (every non-null recommended
 * field is emitted); a container present in {@code current} and absent from {@code recommended} is
 * skipped; a field is emitted only when the recommended value is non-null and differs
 * ({@link Objects#equals}) from the current one, else it is null ("leave alone"); a container whose four
 * fields all diff to null is omitted; containers match by exact name, and a duplicate name in either
 * list is a programming error ({@link IllegalArgumentException}).
 */
final class WorkloadApplyBodyMapper {

    private WorkloadApplyBodyMapper() {
    }

    /** One container's resources in the units the apply API and the params JSON use; any field may be null. */
    record ContainerResources(String name, Integer cpuRequestM, Integer cpuLimitM,
                              Long memRequestMi, Long memLimitMi) {
    }

    static List<ApplyRequestDto.Container> diff(List<ContainerResources> current,
                                                List<ContainerResources> recommended) {
        Map<String, ContainerResources> currentByName = index(current, "current");
        index(recommended, "recommended"); // duplicate check only
        List<ApplyRequestDto.Container> out = new ArrayList<>();
        for (ContainerResources rec : recommended) {
            ContainerResources cur = currentByName.get(rec.name());
            Integer cpuRequestM = changed(rec.cpuRequestM(), cur == null ? null : cur.cpuRequestM());
            Long memRequestMi = changed(rec.memRequestMi(), cur == null ? null : cur.memRequestMi());
            Integer cpuLimitM = changed(rec.cpuLimitM(), cur == null ? null : cur.cpuLimitM());
            Long memLimitMi = changed(rec.memLimitMi(), cur == null ? null : cur.memLimitMi());
            if (cpuRequestM == null && memRequestMi == null && cpuLimitM == null && memLimitMi == null) {
                continue;
            }
            out.add(new ApplyRequestDto.Container(rec.name(), cpuRequestM, memRequestMi, cpuLimitM, memLimitMi));
        }
        return out;
    }

    private static Map<String, ContainerResources> index(List<ContainerResources> list, String which) {
        Map<String, ContainerResources> byName = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (ContainerResources c : list) {
            if (!seen.add(c.name())) {
                throw new IllegalArgumentException("duplicate container name in " + which + ": " + c.name());
            }
            byName.put(c.name(), c);
        }
        return byName;
    }

    /** @return {@code recommended} when it differs from {@code current}, else null: "leave it alone" */
    private static <T> T changed(T recommended, T current) {
        if (recommended == null) {
            return null;
        }
        return Objects.equals(recommended, current) ? null : recommended;
    }
}
