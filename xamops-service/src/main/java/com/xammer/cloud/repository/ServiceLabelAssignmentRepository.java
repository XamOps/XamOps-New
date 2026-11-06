package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ServiceLabelAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ServiceLabelAssignmentRepository extends JpaRepository<ServiceLabelAssignment, Long> {

    /**
     * Every service label this client has put on one account.
     *
     * <p>Client-scoped for the same reason as the resource-label bulk read: an account is reachable
     * by its provider id, which is not a secret.
     */
    @Query("SELECT a FROM ServiceLabelAssignment a JOIN FETCH a.label l "
            + "WHERE a.cloudAccount.id = :accountId AND l.client.id = :clientId")
    List<ServiceLabelAssignment> findByCloudAccountIdForClient(
            @Param("accountId") Long accountId,
            @Param("clientId") Long clientId);

    /** Every service row of one label, across accounts. Read before the label is deleted, for its audit entry. */
    @Query("SELECT a FROM ServiceLabelAssignment a JOIN FETCH a.label WHERE a.label.id = :labelId")
    List<ServiceLabelAssignment> findAllForLabel(@Param("labelId") Long labelId);

    /** The client's labels on one service of one account. */
    @Query("SELECT a FROM ServiceLabelAssignment a JOIN FETCH a.label l "
            + "WHERE a.cloudAccount.id = :accountId AND a.serviceName = :serviceName "
            + "AND l.client.id = :clientId")
    List<ServiceLabelAssignment> findForService(
            @Param("accountId") Long accountId,
            @Param("serviceName") String serviceName,
            @Param("clientId") Long clientId);

    /**
     * Explicit rather than left to the foreign key: on databases where Hibernate created the
     * table, the key has no ON DELETE CASCADE.
     */
    @Modifying
    @Transactional
    void deleteByLabelId(Long labelId);

    @Modifying
    @Transactional
    void deleteByCloudAccountId(Long accountId);
}
