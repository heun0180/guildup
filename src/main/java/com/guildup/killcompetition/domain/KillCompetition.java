package com.guildup.killcompetition.domain;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "kill_competitions", indexes = @Index(
        name = "idx_kill_competition_community_status", columnList = "community_id, status, ends_at"
))
public class KillCompetition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_member_id", nullable = false)
    private CommunityMember createdBy;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING) @Column(name = "game_mode", nullable = false, length = 16)
    private KillCompetitionGameMode gameMode;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private KillCompetitionStatus status;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "recruitment_closed_at") private Instant recruitmentClosedAt;
    @Column(name = "last_interim_calculated_at") private Instant lastInterimCalculatedAt;
    @Column(name = "interim_calculation_started_at") private Instant interimCalculationStartedAt;
    @Column(name = "finalization_started_at") private Instant finalizationStartedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @Version private long version;

    @OneToMany(mappedBy = "competition", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    private Set<KillCompetitionParticipant> participants = new LinkedHashSet<>();

    @OneToMany(mappedBy = "competition", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder asc")
    private Set<KillCompetitionTeam> teams = new LinkedHashSet<>();

    protected KillCompetition() {}

    public KillCompetition(Community community, CommunityMember createdBy, String title,
                           KillCompetitionGameMode gameMode, Instant endsAt, Instant now) {
        this.community = community;
        this.createdBy = createdBy;
        this.title = title;
        this.gameMode = gameMode;
        this.endsAt = endsAt;
        this.status = KillCompetitionStatus.RECRUITING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void closeRecruitment(Instant now) {
        status = KillCompetitionStatus.READY;
        recruitmentClosedAt = now;
        updatedAt = now;
    }

    public void start(Instant now) {
        status = KillCompetitionStatus.IN_PROGRESS;
        startedAt = now;
        updatedAt = now;
    }

    public void beginInterim(Instant now) { interimCalculationStartedAt = now; updatedAt = now; }
    public void finishInterim(Instant now) {
        lastInterimCalculatedAt = now;
        interimCalculationStartedAt = null;
        updatedAt = now;
    }
    public void clearInterimClaim() { interimCalculationStartedAt = null; }
    public void beginFinalization(Instant now) { finalizationStartedAt = now; updatedAt = now; }
    public void clearFinalizationClaim() { finalizationStartedAt = null; }
    public void complete(Instant now) {
        status = KillCompetitionStatus.COMPLETED;
        completedAt = now;
        finalizationStartedAt = null;
        updatedAt = now;
    }
    public void cancel(Instant now) { status = KillCompetitionStatus.CANCELLED; updatedAt = now; }
    public void replaceTeams(List<KillCompetitionTeam> newTeams) {
        participants.forEach(p -> p.assignTeam(null));
        teams.clear();
        teams.addAll(newTeams);
    }
    public void addParticipant(KillCompetitionParticipant participant) { participants.add(participant); }
    public void removeParticipant(KillCompetitionParticipant participant) { participants.remove(participant); }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public CommunityMember getCreatedBy() { return createdBy; }
    public String getTitle() { return title; }
    public KillCompetitionGameMode getGameMode() { return gameMode; }
    public KillCompetitionStatus getStatus() { return status; }
    public Instant getEndsAt() { return endsAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getRecruitmentClosedAt() { return recruitmentClosedAt; }
    public Instant getLastInterimCalculatedAt() { return lastInterimCalculatedAt; }
    public Instant getInterimCalculationStartedAt() { return interimCalculationStartedAt; }
    public Instant getFinalizationStartedAt() { return finalizationStartedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<KillCompetitionParticipant> getParticipants() { return participants.stream().toList(); }
    public List<KillCompetitionTeam> getTeams() { return teams.stream().toList(); }
}
