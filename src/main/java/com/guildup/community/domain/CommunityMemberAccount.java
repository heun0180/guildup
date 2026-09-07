package com.guildup.community.domain;

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

import java.util.Objects;

/** 클랜원과 Discord, PUBG 등 외부 서비스 계정의 연결이다. */
@Entity
@Table(
        name = "community_member_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_member_account_provider",
                columnNames = {"community_member_id", "provider"}
        )
)
public class CommunityMemberAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityMember communityMember;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExternalAccountProvider provider;

    @Column(name = "external_user_id", nullable = false)
    private String externalUserId;

    @Column(name = "external_username")
    private String externalUsername;

    protected CommunityMemberAccount() {
    }

    public CommunityMemberAccount(
            CommunityMember communityMember,
            ExternalAccountProvider provider,
            String externalUserId,
            String externalUsername
    ) {
        this.communityMember = communityMember;
        this.provider = provider;
        this.externalUserId = externalUserId;
        this.externalUsername = externalUsername;
    }

    public Long getId() {
        return id;
    }

    public CommunityMember getCommunityMember() {
        return communityMember;
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

    /** 외부 서비스 사용자 이름이 실제로 달라졌을 때만 갱신한다. */
    public boolean updateExternalUsername(String externalUsername) {
        if (Objects.equals(this.externalUsername, externalUsername)) {
            return false;
        }

        this.externalUsername = externalUsername;
        return true;
    }
}
