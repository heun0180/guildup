package com.guildup.user.repository;

import com.guildup.user.domain.User;
import java.util.Optional;

public interface UserLockRepository {
    Optional<User> findForUpdate(Long id);
}
