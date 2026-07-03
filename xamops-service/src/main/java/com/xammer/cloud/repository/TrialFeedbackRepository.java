package com.xammer.cloud.repository;

import com.xammer.cloud.domain.TrialFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrialFeedbackRepository extends JpaRepository<TrialFeedback, Long> {
}
