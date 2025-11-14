package com.xammer.billops.repository;

import com.xammer.billops.domain.CloudFrontPrivateRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CloudFrontPrivateRateRepository extends JpaRepository<CloudFrontPrivateRate, Long> {

    /**
     * Finds all private CloudFront rates associated with a specific client.
     * @param clientId The ID of the client.
     * @return A list of private rates for that client.
     */
    List<CloudFrontPrivateRate> findByClientId(Long clientId);
}