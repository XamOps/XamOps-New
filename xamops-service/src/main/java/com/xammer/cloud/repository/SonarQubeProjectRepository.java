package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SonarQubeProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SonarQubeProjectRepository extends JpaRepository<SonarQubeProject, Long> {

    /**
     * Finds all SonarQube projects configured by a specific user.
     * @param userId The ID of the user.
     * @return A list of SonarQube projects.
     */
    List<SonarQubeProject> findByUserId(Long userId);
}