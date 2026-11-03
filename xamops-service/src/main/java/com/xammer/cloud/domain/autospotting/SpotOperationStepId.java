package com.xammer.cloud.domain.autospotting;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key of {@link SpotOperationStep}: {@code (operation_id, seq)} (design-core.md §4.5).
 */
public class SpotOperationStepId implements Serializable {

    private UUID operationId;
    private int seq;

    public SpotOperationStepId() {
    }

    public SpotOperationStepId(UUID operationId, int seq) {
        this.operationId = operationId;
        this.seq = seq;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public void setOperationId(UUID operationId) {
        this.operationId = operationId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpotOperationStepId that)) {
            return false;
        }
        return seq == that.seq && Objects.equals(operationId, that.operationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationId, seq);
    }
}
