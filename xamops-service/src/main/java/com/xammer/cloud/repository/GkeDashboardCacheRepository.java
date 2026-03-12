package com.xammer.cloud.repository;

import com.xammer.cloud.domain.GkeDashboardCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GkeDashboardCacheRepository extends JpaRepository<GkeDashboardCache, Long> {

    Optional<GkeDashboardCache> findByClusterNameAndProjectIdAndLocation(
            String clusterName, String projectId, String location);

    List<GkeDashboardCache> findByProjectId(String projectId);

    void deleteByClusterNameAndProjectIdAndLocation(String clusterName, String projectId, String location);

    boolean existsByClusterNameAndProjectIdAndLocation(String clusterName, String projectId, String location);
}
