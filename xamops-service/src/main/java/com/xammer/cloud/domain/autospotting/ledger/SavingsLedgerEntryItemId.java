package com.xammer.cloud.domain.autospotting.ledger;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key of {@link SavingsLedgerEntryItem}: the entry and the item's position in it. */
public class SavingsLedgerEntryItemId implements Serializable {

    private UUID entryId;
    private int seq;

    public SavingsLedgerEntryItemId() {
    }

    public SavingsLedgerEntryItemId(UUID entryId, int seq) {
        this.entryId = entryId;
        this.seq = seq;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public int getSeq() {
        return seq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SavingsLedgerEntryItemId other)) {
            return false;
        }
        return seq == other.seq && Objects.equals(entryId, other.entryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entryId, seq);
    }
}
