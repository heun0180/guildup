package com.guildup.user.repository;

import com.guildup.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

/** GuildUp 자체 사용자를 저장하고 조회한다. */
public interface UserRepository extends JpaRepository<User, Long> {
}
