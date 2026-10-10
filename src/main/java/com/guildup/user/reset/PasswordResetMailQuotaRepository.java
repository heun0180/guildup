package com.guildup.user.reset;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface PasswordResetMailQuotaRepository extends JpaRepository<PasswordResetMailQuota, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from PasswordResetMailQuota q where q.id = 1")
    Optional<PasswordResetMailQuota> locked();
}
