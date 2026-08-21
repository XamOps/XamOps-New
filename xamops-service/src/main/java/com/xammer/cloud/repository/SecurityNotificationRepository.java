package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SecurityNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** The in-app findings inbox, scoped to a client. */
@Repository
public interface SecurityNotificationRepository extends JpaRepository<SecurityNotification, Long> {

    @Query("SELECT n FROM SecurityNotification n WHERE n.clientId = :clientId "
            + "ORDER BY n.createdAt DESC")
    List<SecurityNotification> findForClient(@Param("clientId") Long clientId, Pageable pageable);

    /** Backs the bell counter. */
    long countByClientIdAndReadFalse(Long clientId);

    /**
     * Marks one notification read, scoped by client in the same statement.
     *
     * <p>
     * The client id is part of the {@code WHERE} rather than checked beforehand so a request
     * carrying someone else's notification id updates zero rows instead of theirs. Checking
     * first and then updating by id alone is the same bug with more steps.
     */
    @Modifying
    @Query("UPDATE SecurityNotification n SET n.read = true "
            + "WHERE n.id = :id AND n.clientId = :clientId")
    int markRead(@Param("id") Long id, @Param("clientId") Long clientId);

    @Modifying
    @Query("UPDATE SecurityNotification n SET n.read = true "
            + "WHERE n.clientId = :clientId AND n.read = false")
    int markAllRead(@Param("clientId") Long clientId);

    /** Retention: the inbox is a feed, not an archive. */
    @Modifying
    @Query("DELETE FROM SecurityNotification n WHERE n.createdAt < :before")
    int purgeBefore(@Param("before") Instant before);
}
