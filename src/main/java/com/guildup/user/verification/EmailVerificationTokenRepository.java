package com.guildup.user.verification;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    Optional<EmailVerificationToken> findByTokenHash(String hash);
    Optional<EmailVerificationToken> findFirstByCredentialIdOrderByCreatedAtDescIdDesc(Long credentialId);
    List<EmailVerificationToken> findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(Long credentialId);
    List<EmailVerificationToken> findByCredentialIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(Long credentialId, Instant since);
    long countByCredentialIdAndCreatedAtGreaterThanEqual(Long credentialId, Instant since);
    List<EmailVerificationToken> findByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff, Pageable page);
    @Query("select t.credential.user.id from EmailVerificationToken t where t.tokenHash = :hash")
    Optional<Long> ownerByHash(String hash);
    @Query("select t.credential.user.id from EmailVerificationToken t where t.id = :id")
    Optional<Long> ownerById(Long id);
}
