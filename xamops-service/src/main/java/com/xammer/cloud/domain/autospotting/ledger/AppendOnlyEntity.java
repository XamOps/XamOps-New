package com.xammer.cloud.domain.autospotting.ledger;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;

/**
 * Base of every append-only ledger entity (design-ledger.md §2.4, FR-LED-13). A new instance reports
 * {@link #isNew()} true, so Spring Data's {@code save} issues {@code persist} (an INSERT) and never a
 * {@code merge}. Once the row is persisted or loaded it reports false, and any later change or
 * removal is refused by the JPA callbacks here before a statement is sent; the database trigger
 * ({@code ledger rows are append-only}) is the backstop behind them.
 *
 * @param <ID> the primary key type (a composite key class for item and line tables)
 */
@MappedSuperclass
public abstract class AppendOnlyEntity<ID extends Serializable> implements Persistable<ID> {

    @Transient
    private boolean inserted;

    @Override
    public boolean isNew() {
        return !inserted;
    }

    @PostLoad
    @PostPersist
    void markInserted() {
        this.inserted = true;
    }

    @PreUpdate
    void refuseUpdate() {
        throw new IllegalStateException("ledger rows are append-only: " + getClass().getSimpleName()
                + " cannot be updated");
    }

    @PreRemove
    void refuseRemove() {
        throw new IllegalStateException("ledger rows are append-only: " + getClass().getSimpleName()
                + " cannot be removed");
    }
}
