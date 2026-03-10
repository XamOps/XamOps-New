package com.xammer.billops.repository;

import com.xammer.cloud.domain.CachedData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BillOpsCachedDataRepository extends JpaRepository<CachedData, String> {
}
