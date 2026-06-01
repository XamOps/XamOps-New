package com.xammer.billops.repository;

import com.xammer.billops.domain.ClientIntakeSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientIntakeRepository extends JpaRepository<ClientIntakeSubmission, Long> {
    List<ClientIntakeSubmission> findAllByOrderBySubmittedAtDesc();
}
