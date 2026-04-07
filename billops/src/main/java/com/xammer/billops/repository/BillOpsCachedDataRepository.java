package com.xammer.billops.repository;

import com.xammer.cloud.domain.CachedData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BillOpsCachedDataRepository extends JpaRepository<CachedData, String> {
    Optional<CachedData> findFirstByCacheKeyStartingWith(String prefix);
}
