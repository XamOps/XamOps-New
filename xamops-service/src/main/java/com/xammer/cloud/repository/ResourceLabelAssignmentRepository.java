package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ResourceLabelAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ResourceLabelAssignmentRepository extends JpaRepository<ResourceLabelAssignment, Long> {

    // All assignments for a specific label (paginated — for "View by label" page)
    Page<ResourceLabelAssignment> findByLabelId(Long labelId, Pageable pageable);

    // All labels assigned to one resource in one account
    @Query("SELECT a FROM ResourceLabelAssignment a JOIN FETCH a.label WHERE a.cloudAccount.id = :accountId AND a.resourceId = :resourceId")
    List<ResourceLabelAssignment> findByCloudAccountIdAndResourceId(@Param("accountId") Long accountId, @Param("resourceId") String resourceId);

    // Bulk fetch: all label assignments for a set of resource IDs in one account
    // Used to enrich an entire Cloud List page in a single query (no N+1)
    @Query("SELECT a FROM ResourceLabelAssignment a JOIN FETCH a.label WHERE a.cloudAccount.id = :accountId AND a.resourceId IN :resourceIds")
    List<ResourceLabelAssignment> findByCloudAccountIdAndResourceIdIn(@Param("accountId") Long accountId, @Param("resourceIds") List<String> resourceIds);

    /**
     * As above, but only labels belonging to the given client.
     *
     * <p>
     * The tenant filter is the point. A cloud_account row is reachable by its provider
     * account id, which is not a secret, so scoping this read by account alone lets any
     * signed-in user read another tenant's labels for an account id they can name. Every
     * other label route scopes through {@code findByIdAndClientId}; this one is the bulk
     * path and has to do the same.
     *
     * <p>
     * {@code l.client.id} compares the foreign key on resource_labels and adds no join, so
     * this costs the same as the unscoped variant.
     */
    @Query("SELECT a FROM ResourceLabelAssignment a JOIN FETCH a.label l "
            + "WHERE a.cloudAccount.id = :accountId AND a.resourceId IN :resourceIds "
            + "AND l.client.id = :clientId")
    List<ResourceLabelAssignment> findByCloudAccountIdAndResourceIdInForClient(
            @Param("accountId") Long accountId,
            @Param("resourceIds") List<String> resourceIds,
            @Param("clientId") Long clientId);

    // Delete a specific resource → label mapping
    void deleteByCloudAccountIdAndResourceIdAndLabelId(Long accountId, String resourceId, Long labelId);

    // Delete all labels from a resource (e.g. when resource is removed from all labels)
    void deleteByCloudAccountIdAndResourceId(Long accountId, String resourceId);

    // Delete all label assignments for an account (used during account deletion)
    @Modifying
    @Transactional
    void deleteByCloudAccountId(Long accountId);

    // Count resources for a label (for label summary cards)
    long countByLabelId(Long labelId);

    // Check if an assignment already exists (for idempotent assign)
    boolean existsByCloudAccountIdAndResourceIdAndLabelId(Long accountId, String resourceId, Long labelId);

    // All resource IDs assigned to a label (for cost filtering)
    @Query("SELECT a.resourceId FROM ResourceLabelAssignment a WHERE a.label.id = :labelId")
    List<String> findResourceIdsByLabelId(@Param("labelId") Long labelId);

    // Distinct resource types assigned to a label (for service-level cost fallback)
    @Query("SELECT DISTINCT a.resourceType FROM ResourceLabelAssignment a WHERE a.label.id = :labelId")
    List<String> findDistinctResourceTypesByLabelId(@Param("labelId") Long labelId);

    // All assignment details for a label (used to include resource names in cost response)
    @Query("SELECT a FROM ResourceLabelAssignment a WHERE a.label.id = :labelId")
    List<ResourceLabelAssignment> findAllAssignmentsByLabelId(@Param("labelId") Long labelId);

    // Account-scoped: only resources assigned to a label within a specific AWS account
    @Query("SELECT a FROM ResourceLabelAssignment a WHERE a.label.id = :labelId AND a.cloudAccount.awsAccountId = :awsAccountId")
    List<ResourceLabelAssignment> findAllAssignmentsByLabelIdAndAwsAccountId(
            @Param("labelId") Long labelId, @Param("awsAccountId") String awsAccountId);
}
