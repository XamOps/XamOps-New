package com.xammer.cloud.repository.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.ledger.AutoSpottingContract;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contracts (design-ledger.md §2.3). Deliberately not a {@code JpaRepository}: a contract is saved
 * and found, never deleted (the trigger refuses a DELETE too), and its only update is to end it.
 */
public interface AutoSpottingContractRepository extends Repository<AutoSpottingContract, UUID> {

    <S extends AutoSpottingContract> S save(S contract);

    <S extends AutoSpottingContract> S saveAndFlush(S contract);

    Optional<AutoSpottingContract> findById(UUID id);

    /** Every contract of the client, newest first. */
    List<AutoSpottingContract> findByClientIdOrderByEffectiveFromDesc(Long clientId);

    /** The contract in force at {@code at} ({@code effective_from <= at < effective_to}); at most one exists. */
    @Query("select c from AutoSpottingContract c where c.clientId = :clientId and c.effectiveFrom <= :at "
            + "and (c.effectiveTo is null or c.effectiveTo > :at)")
    List<AutoSpottingContract> findInForce(@Param("clientId") Long clientId, @Param("at") Instant at);
}
