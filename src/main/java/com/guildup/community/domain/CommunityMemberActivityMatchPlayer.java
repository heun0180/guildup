package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "community_member_activity_match_players")
public class CommunityMemberActivityMatchPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_match_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityMemberActivityMatch activityMatch;

    @Column(name = "pubg_account_id")
    private String pubgAccountId;

    @Column(name = "pubg_nickname")
    private String pubgNickname;

    @Column(name = "clan_member", nullable = false)
    private boolean clanMember;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "community_member_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private CommunityMember communityMember;

    protected CommunityMemberActivityMatchPlayer() {
    }

    public CommunityMemberActivityMatchPlayer(
            CommunityMemberActivityMatch activityMatch,
            String pubgAccountId,
            String pubgNickname,
            CommunityMember communityMember
    ) {
        this.activityMatch = activityMatch;
        this.pubgAccountId = pubgAccountId;
        this.pubgNickname = pubgNickname;
        this.communityMember = communityMember;
        this.clanMember = communityMember != null;
    }

    public String getPubgAccountId() { return pubgAccountId; }
    public String getPubgNickname() { return pubgNickname; }
    public boolean isClanMember() { return clanMember; }
    public CommunityMember getCommunityMember() { return communityMember; }
}
