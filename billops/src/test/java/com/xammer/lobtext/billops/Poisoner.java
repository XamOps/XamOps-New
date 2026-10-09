package com.xammer.lobtext.billops;

import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises a real PostgreSQL error (design §7.2, AC 21): {@code SELECT lo_get(0)} fails with "large object
 * 0 does not exist". It runs through the shared {@link EntityManager} with default propagation, so it
 * joins whatever transaction its caller is in. Inside a method-wide transaction (the pre-change
 * dashboard), that transaction is aborted and marked rollback-only; without one, only this call's own
 * read-only transaction rolls back.
 */
public class Poisoner {

    private final EntityManager entityManager;

    public Poisoner(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public void poison() {
        entityManager.createNativeQuery("SELECT lo_get(0)").getSingleResult();
    }
}
