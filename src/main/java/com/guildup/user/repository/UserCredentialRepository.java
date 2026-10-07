package com.guildup.user.repository;

import com.guildup.user.domain.UserCredential;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserCredentialRepository extends JpaRepository<UserCredential, Long> {
    @EntityGraph(attributePaths = "user")
    Optional<UserCredential> findByEmail(String email);
    Optional<UserCredential> findByUserId(Long userId);
    boolean existsByEmail(String email);
    boolean existsByUserId(Long userId);
}
