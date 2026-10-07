package com.guildup.user.repository;

import com.guildup.user.domain.User;
import jakarta.persistence.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** OSIV/이전 읽음에서 캐시된 ACTIVE 객체도 잠금 이후 DB 상태로 다시 읽는다. */
public class UserLockRepositoryImpl implements UserLockRepository {
    @PersistenceContext private EntityManager em;
    @Override @Transactional
    public Optional<User> findForUpdate(Long id) {
        User user = em.find(User.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (user != null) em.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return Optional.ofNullable(user);
    }
}
