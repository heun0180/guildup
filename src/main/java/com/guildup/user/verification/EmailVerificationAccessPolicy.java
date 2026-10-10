package com.guildup.user.verification;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.user.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationAccessPolicy {
    private final EmailVerificationProperties properties;
    private final UserCredentialRepository credentials;
    private final UserExternalAccountRepository external;
    public EmailVerificationAccessPolicy(EmailVerificationProperties properties, UserCredentialRepository credentials,
                                         UserExternalAccountRepository external) {
        this.properties = properties; this.credentials = credentials; this.external = external;
    }
    @Transactional(readOnly = true)
    public boolean restricted(Long userId) {
        if (!properties.enforceNewUsers()) return false;
        return credentials.findByUserId(userId).filter(value -> value.isVerificationRequired() && !value.isEmailVerified())
                .isPresent() && external.findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD).isEmpty();
    }
}
