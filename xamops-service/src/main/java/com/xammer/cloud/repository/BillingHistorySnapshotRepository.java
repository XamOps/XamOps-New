package com.xammer.cloud.repository;

import com.xammer.cloud.domain.BillingHistorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingHistorySnapshotRepository extends JpaRepository<BillingHistorySnapshot, Long> {

    List<BillingHistorySnapshot> findByAwsAccountIdOrderByYearAscMonthAsc(String awsAccountId);

    boolean existsByAwsAccountId(String awsAccountId);

    void deleteByAwsAccountId(String awsAccountId);
}
