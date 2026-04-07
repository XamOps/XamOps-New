package com.xammer.cloud.repository;

import com.xammer.cloud.domain.WorkspaceUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkspaceUserRepository extends JpaRepository<WorkspaceUser, Long> {

    List<WorkspaceUser> findByConnectionId(Long connectionId);

    List<WorkspaceUser> findByConnectionIdOrderByPrimaryEmailAsc(Long connectionId);

    List<WorkspaceUser> findByConnectionIdAndUserStatus(Long connectionId, String userStatus);

    void deleteByConnectionId(Long connectionId);

    long countByConnectionId(Long connectionId);
}
