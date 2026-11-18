package com.xammer.cloud.domain.autospotting.ledger;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key of {@link SavingsStatementLine}: the statement and the line number. */
public class SavingsStatementLineId implements Serializable {

    private UUID statementId;
    private int lineNo;

    public SavingsStatementLineId() {
    }

    public SavingsStatementLineId(UUID statementId, int lineNo) {
        this.statementId = statementId;
        this.lineNo = lineNo;
    }

    public UUID getStatementId() {
        return statementId;
    }

    public int getLineNo() {
        return lineNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SavingsStatementLineId other)) {
            return false;
        }
        return lineNo == other.lineNo && Objects.equals(statementId, other.statementId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(statementId, lineNo);
    }
}
