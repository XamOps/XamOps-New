package com.xammer.cloud.repository;

import com.xammer.cloud.domain.User;

import io.lettuce.core.dynamic.annotation.Param;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
@Query("SELECT u FROM User u JOIN FETCH u.client WHERE u.username = :username")
Optional<User> findByUsername(@Param("username") String username);
}
