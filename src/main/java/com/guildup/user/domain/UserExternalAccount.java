package com.guildup.user.domain;

import com.guildup.account.domain.ExternalAccountProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** GuildUp 사용자와 외부 서비스 계정의 연결이다. */
@Entity
@Table(
        name = "user_external_accounts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_external_account_user_provider",
                        columnNames = {"user_id", "provider"}
                ),
                @UniqueConstraint(
                        name = "uk_user_external_account_provider_id",
                        columnNames = {"provider", "external_user_id"}
                )
        }
)
public class UserExternalAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExternalAccountProvider provider;

    @Column(name = "external_user_id", nullable = false)
    private String externalUserId;

    @Column(name = "external_username")
    private String externalUsername;

    protected UserExternalAccount() {
    }

    public UserExternalAccount(
            User user,
            ExternalAccountProvider provider,
            String externalUserId,
            String externalUsername
    ) {
        this.user = user;
        this.provider = provider;
        this.externalUserId = externalUserId;
        this.externalUsername = externalUsername;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public ExternalAccountProvider getProvider() {
        return provider;
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public String getExternalUsername() {
        return externalUsername;
    }
}
