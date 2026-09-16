package com.guildup.killcompetition.domain;

import com.guildup.community.domain.CommunityMember;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(name = "kill_competition_participants", uniqueConstraints = @UniqueConstraint(
        name = "uk_kill_competition_participant", columnNames = {"competition_id", "community_member_id"}
))
public class KillCompetitionParticipant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private KillCompetition competition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_member_id", nullable = false)
    private CommunityMember communityMember;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private KillCompetitionTeam team;
    @Column(name = "pubg_account_id", nullable = false) private String pubgAccountId;
    @Column(name = "pubg_nickname", nullable = false) private String pubgNickname;
    @Enumerated(EnumType.STRING)
    @Column(name = "participation_status", nullable = false, length = 16,
            columnDefinition = "varchar(16) default 'APPROVED'")
    private KillCompetitionParticipationStatus participationStatus;
    @Column(name = "eligible_from") private Instant eligibleFrom;
    @Column(name = "interim_kills", nullable = false) private int interimKills;
    @Column(name = "interim_match_count", nullable = false) private int interimMatchCount;
    @Column(name = "final_kills") private Integer finalKills;
    @Column(name = "final_match_count") private Integer finalMatchCount;

    protected KillCompetitionParticipant() {}
    public KillCompetitionParticipant(KillCompetition competition, CommunityMember member,
                                      String pubgAccountId, String pubgNickname) {
        this(competition, member, pubgAccountId, pubgNickname, KillCompetitionParticipationStatus.APPROVED);
    }
    public KillCompetitionParticipant(KillCompetition competition, CommunityMember member,
                                      String pubgAccountId, String pubgNickname,
                                      KillCompetitionParticipationStatus participationStatus) {
        this.competition = competition;
        this.communityMember = member;
        this.pubgAccountId = pubgAccountId;
        this.pubgNickname = pubgNickname;
        this.participationStatus = participationStatus;
    }
    public void assignTeam(KillCompetitionTeam team) { this.team = team; }
    public void approve(Instant eligibleFrom, KillCompetitionTeam team) {
        participationStatus = KillCompetitionParticipationStatus.APPROVED;
        this.eligibleFrom = eligibleFrom;
        this.team = team;
    }
    public void initializeEligibleFrom(Instant startedAt) {
        if (eligibleFrom == null) eligibleFrom = startedAt;
    }
    public void recordInterim(int kills, int matchCount) { interimKills = kills; interimMatchCount = matchCount; }
    public void recordFinal(int kills, int matchCount) { finalKills = kills; finalMatchCount = matchCount; }
    public Long getId() { return id; }
    public KillCompetition getCompetition() { return competition; }
    public CommunityMember getCommunityMember() { return communityMember; }
    public KillCompetitionTeam getTeam() { return team; }
    public String getPubgAccountId() { return pubgAccountId; }
    public String getPubgNickname() { return pubgNickname; }
    public KillCompetitionParticipationStatus getParticipationStatus() { return participationStatus; }
    public boolean isApproved() { return participationStatus == KillCompetitionParticipationStatus.APPROVED; }
    public Instant getEligibleFrom() { return eligibleFrom; }
    public int getInterimKills() { return interimKills; }
    public int getInterimMatchCount() { return interimMatchCount; }
    public Integer getFinalKills() { return finalKills; }
    public Integer getFinalMatchCount() { return finalMatchCount; }
}
