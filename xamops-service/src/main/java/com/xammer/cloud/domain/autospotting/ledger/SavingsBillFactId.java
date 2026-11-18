package com.xammer.cloud.domain.autospotting.ledger;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key of {@link SavingsBillFact}: the import and the fact's position in it. */
public class SavingsBillFactId implements Serializable {

    private UUID importId;
    private int seq;

    public SavingsBillFactId() {
    }

    public SavingsBillFactId(UUID importId, int seq) {
        this.importId = importId;
        this.seq = seq;
    }

    public UUID getImportId() {
        return importId;
    }

    public int getSeq() {
        return seq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SavingsBillFactId other)) {
            return false;
        }
        return seq == other.seq && Objects.equals(importId, other.importId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(importId, seq);
    }
}
