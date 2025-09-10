package com.xammer.billops.repository;

// Update the import path to match the actual location of the User class
import com.xammer.billops.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}