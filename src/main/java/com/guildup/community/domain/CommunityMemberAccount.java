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

import java.time.Instant;
import java.util.Objects;

/** 클랜원과 Discord, PUBG 등 외부 서비스 계정의 연결이다. */
@Entity
@Table(
        name = "community_member_accounts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_community_member_account_provider",
                        columnNames = {"community_member_id", "provider"}
                ),
                @UniqueConstraint(
                        name = "uk_community_member_account_community_provider_user",
                        columnNames = {"community_id", "provider", "external_user_id"}
                )
        }
)
public class CommunityMemberAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityMember communityMember;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExternalAccountProvider provider;

    @Column(name = "external_user_id", nullable = false)
    private String externalUserId;

    @Column(name = "external_username")
    private String externalUsername;

    @Column(name = "external_display_name")
    private String externalDisplayName;

    @Column(name = "external_joined_at")
    private Instant externalJoinedAt;

    protected CommunityMemberAccount() {
    }

    public CommunityMemberAccount(
            CommunityMember communityMember,
            ExternalAccountProvider provider,
            String externalUserId,
            String externalUsername
    ) {
        this.communityMember = communityMember;
        this.community = communityMember.getCommunity();
        this.provider = provider;
        this.externalUserId = externalUserId;
        this.externalUsername = externalUsername;
    }

    public CommunityMemberAccount(
            CommunityMember communityMember,
            ExternalAccountProvider provider,
            String externalUserId,
            String externalUsername,
            String externalDisplayName,
            Instant externalJoinedAt
    ) {
        this(communityMember, provider, externalUserId, externalUsername);
        this.externalDisplayName = externalDisplayName;
        this.externalJoinedAt = externalJoinedAt;
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

    public Community getCommunity() {
        return community;
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public String getExternalUsername() {
        return externalUsername;
    }

    public String getExternalDisplayName() {
        return externalDisplayName;
    }

    public Instant getExternalJoinedAt() {
        return externalJoinedAt;
    }

    /** 외부 서비스 사용자 이름이 실제로 달라졌을 때만 갱신한다. */
    public boolean updateExternalUsername(String externalUsername) {
        if (Objects.equals(this.externalUsername, externalUsername)) {
            return false;
        }

        this.externalUsername = externalUsername;
        return true;
    }

    /** Discord 계정의 화면 표시 정보를 한 번에 최신 상태로 갱신한다. */
    public void synchronizeExternalProfile(
            String externalUsername,
            String externalDisplayName,
            Instant externalJoinedAt
    ) {
        this.externalUsername = externalUsername;
        this.externalDisplayName = externalDisplayName;
        if (externalJoinedAt != null) {
            this.externalJoinedAt = externalJoinedAt;
        }
    }
}
