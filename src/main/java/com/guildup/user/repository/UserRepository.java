package com.guildup.user.repository;

import com.guildup.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

/** GuildUp 자체 사용자를 저장하고 조회한다. */
public interface UserRepository extends JpaRepository<User, Long>, UserLockRepository {
    /** Scalar projection bypasses the OSIV/transaction entity cache. */
    @org.springframework.data.jpa.repository.Query("select u.authenticationVersion as version, u.status as status from User u where u.id = :id")
    java.util.Optional<AuthenticationState> authenticationState(Long id);
    interface AuthenticationState {
        long getVersion();
        com.guildup.user.domain.UserStatus getStatus();
    }
}
