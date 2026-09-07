package com.guildup.user.repository;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.user.domain.UserExternalAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

/** GuildUp 사용자의 외부 서비스 계정 연결을 저장하고 조회한다. */
public interface UserExternalAccountRepository extends JpaRepository<UserExternalAccount, Long> {

    List<UserExternalAccount> findByUserId(Long userId);

    Optional<UserExternalAccount> findByUserIdAndProvider(Long userId, ExternalAccountProvider provider);

    @EntityGraph(attributePaths = "user")
    Optional<UserExternalAccount> findByProviderAndExternalUserId(
            ExternalAccountProvider provider,
            String externalUserId
    );
}
