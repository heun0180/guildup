package com.guildup.developer.config;

import com.guildup.user.domain.SystemRole;
import com.guildup.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 커뮤니티/Discord 권한을 보지 않고 GuildUp 시스템 권한만 검증한다. */
@Service
@Transactional(readOnly = true)
public class DeveloperAccessService {
    private final UserRepository users;

    public DeveloperAccessService(UserRepository users) {
        this.users = users;
    }

    public void requireSystemAdmin(Long userId) {
        boolean allowed = users.findById(userId)
                .map(user -> user.isActive() && user.getSystemRole() == SystemRole.SYSTEM_ADMIN)
                .orElse(false);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System administrator access required");
        }
    }
}
