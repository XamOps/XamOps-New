package com.xammer.billops.repository;

import com.xammer.cloud.domain.AppUser; // ✅ Updated Import
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @Modifying
    @Query("DELETE FROM AppUser u WHERE u.id = :id")
    void deleteByIdDirect(@Param("id") Long id);

    /**
     * Clears the self-referencing parent pointer on any sub-users created by this user, so the
     * row can be deleted without violating app_user.created_by -> app_user(id). The children are
     * kept (their createdBy becomes NULL, i.e. they become primary users) rather than deleted.
     */
    @Modifying
    @Query("UPDATE AppUser u SET u.createdBy = NULL WHERE u.createdBy = :id")
    void detachChildrenCreatedBy(@Param("id") Long id);

    /**
     * Deletes rows in tables that FK to app_user but have no JPA entity in this service
     * (they are owned by xamops-service, which shares the same database). Without clearing
     * these, deleting a user who configured any of these integrations fails with a foreign
     * key constraint violation. Native because billops has no mapped entity for them.
     */
    @Modifying
    @Query(value = "DELETE FROM fin_ops_report_schedule WHERE user_id = :id", nativeQuery = true)
    void deleteFinOpsReportSchedulesByUserId(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM github_integration_config WHERE user_id = :id", nativeQuery = true)
    void deleteGithubIntegrationConfigByUserId(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM jenkins_integration_config WHERE user_id = :id", nativeQuery = true)
    void deleteJenkinsIntegrationConfigByUserId(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM sonarqube_project WHERE user_id = :id", nativeQuery = true)
    void deleteSonarqubeProjectsByUserId(@Param("id") Long id);

    /**
     * Finds all users associated with a specific client ID.
     * @param clientId The ID of the client.
     * @return A list of AppUser entities.
     */
    List<AppUser> findByClientId(Long clientId);

    /**
     * All users of a client whose {@code Client.demo} ({@code is_demo}) flag matches. Derived query
     * over the {@code AppUser.client} {@code @ManyToOne} and {@code Client.demo}
     * ({@code Client_Demo} → {@code client.demo}). Used by the admin "Demo Users" endpoint to
     * enumerate the demo-client user set (id/email/username); login history is read separately from
     * {@code user_activity_event}.
     * @param demo the {@code Client.demo} value to match (pass {@code true} for demo-client users).
     * @return the matching users.
     */
    List<AppUser> findByClient_Demo(boolean demo);

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByEmail(String email);

    /**
     * Finds all users created by a specific user.
     * @param createdBy The ID of the user who created them.
     * @return A list of AppUser entities.
     */
    List<AppUser> findByCreatedBy(Long createdBy);

}