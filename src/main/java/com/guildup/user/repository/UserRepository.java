package com.guildup.user.repository;

import com.guildup.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

/** GuildUp 자체 사용자를 저장하고 조회한다. */
public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select user from User user where user.id = :id")
    java.util.Optional<User> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
