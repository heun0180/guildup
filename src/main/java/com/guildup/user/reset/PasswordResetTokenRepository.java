package com.guildup.user.reset;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByTokenHash(String hash);
    List<PasswordResetToken> findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(Long credentialId);
    List<PasswordResetToken> findByCredentialIdAndCreatedAtGreaterThanOrderByCreatedAtAscIdAsc(Long credentialId, Instant since);
    List<PasswordResetToken> findByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff, Pageable page);
    @Query("select t.credential.user.id from PasswordResetToken t where t.tokenHash = :hash and t.purpose = com.guildup.user.auth.token.AuthTokenPurpose.PASSWORD_RESET")
    Optional<Long> ownerByHash(String hash);
    @Query("select t.credential.user.id from PasswordResetToken t where t.id = :id and t.purpose = com.guildup.user.auth.token.AuthTokenPurpose.PASSWORD_RESET")
    Optional<Long> ownerById(Long id);
}
