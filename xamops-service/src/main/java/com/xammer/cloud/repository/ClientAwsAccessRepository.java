package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ClientAwsAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientAwsAccessRepository extends JpaRepository<ClientAwsAccess, Long> {

    List<ClientAwsAccess> findByClientId(Long clientId);

    Optional<ClientAwsAccess> findByClientIdAndAuthentikGroupId(Long clientId, String authentikGroupId);
}
