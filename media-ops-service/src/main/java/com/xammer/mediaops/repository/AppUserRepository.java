package com.xammer.mediaops.repository;

import com.xammer.mediaops.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
}
