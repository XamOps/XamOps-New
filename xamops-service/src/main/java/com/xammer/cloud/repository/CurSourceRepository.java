package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CurSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CurSourceRepository extends JpaRepository<CurSource, Long> {

    List<CurSource> findByStatus(String status);

    Optional<CurSource> findByS3BucketAndReportName(String s3Bucket, String reportName);

    /**
     * The CUR source for an AWS account, selected directly rather than read off a
     * loaded account.
     *
     * <p>{@code CloudAccount.curSource} is a LAZY {@code @ManyToOne}, and the cost
     * getters run on the {@code awsTaskExecutor} pool with no open session — reading
     * the association off a detached account there throws
     * LazyInitializationException. This is the same reason
     * {@link CloudAccountRepository#findCspBillingConfigByGcpProjectId} exists on the
     * GCP side.
     *
     * <p>Returns empty for an account whose source is not ACTIVE, so a source that
     * failed validation routes to Cost Explorer instead of producing a query against
     * a table that may not exist.
     */
    @Query("SELECT a.curSource FROM CloudAccount a "
         + "WHERE a.awsAccountId = :awsAccountId AND a.curSource IS NOT NULL "
         + "AND a.curSource.status = 'ACTIVE'")
    List<CurSource> findActiveByAwsAccountId(@Param("awsAccountId") String awsAccountId);
}
