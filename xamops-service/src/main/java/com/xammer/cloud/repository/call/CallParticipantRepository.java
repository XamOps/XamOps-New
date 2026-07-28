package com.xammer.cloud.repository.call;

import com.xammer.cloud.domain.call.CallParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CallParticipantRepository extends JpaRepository<CallParticipant, Long> {

    List<CallParticipant> findByCallSessionId(Long callSessionId);

    Optional<CallParticipant> findByCallSessionIdAndUserId(Long callSessionId, Long userId);

    /**
     * Closes out everyone still undecided when a call finishes. Applied in one
     * statement so a large ring set does not turn into N updates.
     *
     * Clears the persistence context afterwards: this is a bulk update that
     * bypasses the first-level cache, so without it a caller reading participants
     * later in the same transaction would still see the pre-update outcomes.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CallParticipant p SET p.outcome = :outcome "
            + "WHERE p.callSessionId = :callSessionId AND p.outcome IS NULL")
    int settleUndecided(@Param("callSessionId") Long callSessionId,
            @Param("outcome") CallParticipant.Outcome outcome);

    List<CallParticipant> findByUserIdAndOutcomeOrderByIdDesc(Long userId, CallParticipant.Outcome outcome);
}
