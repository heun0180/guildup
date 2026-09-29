package com.guildup.killcompetition.domain;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityGame;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "kill_competitions", indexes = {
        @Index(name = "idx_kill_competition_community_status", columnList = "community_id, status, ends_at"),
        @Index(name = "idx_kill_competition_result_publish", columnList = "status, result_publish_at")
})
public class KillCompetition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    // 기존 운영 데이터의 게임 경계를 backfill하기 전에도 Hibernate가 컬럼을 추가할 수 있어야 한다.
    // 새 킬내기는 생성자에서 항상 게임을 지정하며, 시작 마이그레이션이 단일 게임 커뮤니티를 채운다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "community_game_id")
    private CommunityGame communityGame;

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
    @Column(name = "kill_point", nullable = false, columnDefinition = "integer default 1")
    private int killPoint = 1;
    @Column(name = "placement_point_enabled", nullable = false, columnDefinition = "boolean default false")
    private boolean placementPointEnabled;
    @Column(name = "first_place_point", nullable = false, columnDefinition = "integer default 5")
    private int firstPlacePoint = 5;
    @Column(name = "second_place_point", nullable = false, columnDefinition = "integer default 4")
    private int secondPlacePoint = 4;
    @Column(name = "third_place_point", nullable = false, columnDefinition = "integer default 3")
    private int thirdPlacePoint = 3;
    @Column(name = "fourth_fifth_place_point", nullable = false, columnDefinition = "integer default 2")
    private int fourthFifthPlacePoint = 2;
    @Column(name = "sixth_tenth_place_point", nullable = false, columnDefinition = "integer default 1")
    private int sixthTenthPlacePoint = 1;
    @Column(name = "fourth_place_point") private Integer fourthPlacePoint;
    @Column(name = "fifth_place_point") private Integer fifthPlacePoint;
    @Column(name = "sixth_place_point") private Integer sixthPlacePoint;
    @Column(name = "seventh_place_point") private Integer seventhPlacePoint;
    @Column(name = "eighth_place_point") private Integer eighthPlacePoint;
    @Column(name = "ninth_place_point") private Integer ninthPlacePoint;
    @Column(name = "tenth_place_point") private Integer tenthPlacePoint;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "recruitment_open", nullable = false, columnDefinition = "boolean default true") private boolean recruitmentOpen = true;
    @Column(name = "recruitment_closed_at") private Instant recruitmentClosedAt;
    @Column(name = "last_interim_calculated_at") private Instant lastInterimCalculatedAt;
    @Column(name = "last_interim_match_started_at") private Instant lastInterimMatchStartedAt;
    @Column(name = "interim_calculation_started_at") private Instant interimCalculationStartedAt;
    @Column(name = "finalization_started_at") private Instant finalizationStartedAt;
    @Column(name = "finalization_claim_token") private UUID finalizationClaimToken;
    @Column(name = "result_requested_at") private Instant resultRequestedAt;
    @Column(name = "result_publish_at") private Instant resultPublishAt;
    @Column(name = "result_last_error", length = 500) private String resultLastError;
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

    public KillCompetition(Community community, CommunityGame communityGame, CommunityMember createdBy, String title,
                           KillCompetitionGameMode gameMode, Instant endsAt, Instant now) {
        this(community, communityGame, createdBy, title, gameMode, endsAt, now,
                1, false, 5, 4, 3, 2, 1);
    }

    public KillCompetition(Community community, CommunityGame communityGame, CommunityMember createdBy, String title,
                           KillCompetitionGameMode gameMode, Instant endsAt, Instant now,
                           int killPoint, boolean placementPointEnabled,
                           int firstPlacePoint, int secondPlacePoint, int thirdPlacePoint,
                           int fourthFifthPlacePoint, int sixthTenthPlacePoint) {
        this(community, communityGame, createdBy, title, gameMode, endsAt, now, killPoint, placementPointEnabled,
                firstPlacePoint, secondPlacePoint, thirdPlacePoint,
                fourthFifthPlacePoint, fourthFifthPlacePoint,
                sixthTenthPlacePoint, sixthTenthPlacePoint, sixthTenthPlacePoint,
                sixthTenthPlacePoint, sixthTenthPlacePoint);
    }

    public KillCompetition(Community community, CommunityGame communityGame, CommunityMember createdBy, String title,
                           KillCompetitionGameMode gameMode, Instant endsAt, Instant now,
                           int killPoint, boolean placementPointEnabled,
                           int firstPlacePoint, int secondPlacePoint, int thirdPlacePoint,
                           int fourthPlacePoint, int fifthPlacePoint, int sixthPlacePoint,
                           int seventhPlacePoint, int eighthPlacePoint, int ninthPlacePoint, int tenthPlacePoint) {
        this.community = community;
        this.communityGame = communityGame;
        this.createdBy = createdBy;
        this.title = title;
        this.gameMode = gameMode;
        this.endsAt = endsAt;
        updateScoreSettings(killPoint, placementPointEnabled, firstPlacePoint, secondPlacePoint, thirdPlacePoint,
                fourthPlacePoint, fifthPlacePoint, sixthPlacePoint, seventhPlacePoint, eighthPlacePoint,
                ninthPlacePoint, tenthPlacePoint, now);
        this.status = KillCompetitionStatus.RECRUITING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateScoreSettings(int killPoint, boolean placementPointEnabled,
                                    int firstPlacePoint, int secondPlacePoint, int thirdPlacePoint,
                                    int fourthFifthPlacePoint, int sixthTenthPlacePoint, Instant now) {
        updateScoreSettings(killPoint, placementPointEnabled, firstPlacePoint, secondPlacePoint, thirdPlacePoint,
                fourthFifthPlacePoint, fourthFifthPlacePoint,
                sixthTenthPlacePoint, sixthTenthPlacePoint, sixthTenthPlacePoint,
                sixthTenthPlacePoint, sixthTenthPlacePoint, now);
    }

    public void updateScoreSettings(int killPoint, boolean placementPointEnabled,
                                    int firstPlacePoint, int secondPlacePoint, int thirdPlacePoint,
                                    int fourthPlacePoint, int fifthPlacePoint, int sixthPlacePoint,
                                    int seventhPlacePoint, int eighthPlacePoint, int ninthPlacePoint,
                                    int tenthPlacePoint, Instant now) {
        this.killPoint = killPoint;
        this.placementPointEnabled = placementPointEnabled;
        this.firstPlacePoint = firstPlacePoint;
        this.secondPlacePoint = secondPlacePoint;
        this.thirdPlacePoint = thirdPlacePoint;
        this.fourthPlacePoint = fourthPlacePoint;
        this.fifthPlacePoint = fifthPlacePoint;
        this.sixthPlacePoint = sixthPlacePoint;
        this.seventhPlacePoint = seventhPlacePoint;
        this.eighthPlacePoint = eighthPlacePoint;
        this.ninthPlacePoint = ninthPlacePoint;
        this.tenthPlacePoint = tenthPlacePoint;
        this.updatedAt = now;
    }

    public int placementPointFor(int placement) {
        if (!placementPointEnabled) return 0;
        return switch (placement) {
            case 1 -> firstPlacePoint;
            case 2 -> secondPlacePoint;
            case 3 -> thirdPlacePoint;
            case 4 -> getFourthPlacePoint();
            case 5 -> getFifthPlacePoint();
            case 6 -> getSixthPlacePoint();
            case 7 -> getSeventhPlacePoint();
            case 8 -> getEighthPlacePoint();
            case 9 -> getNinthPlacePoint();
            case 10 -> getTenthPlacePoint();
            default -> 0;
        };
    }

    public void closeRecruitment(Instant now) {
        status = KillCompetitionStatus.READY;
        recruitmentClosedAt = now;
        updatedAt = now;
    }

    public void start(Instant now) {
        status = KillCompetitionStatus.IN_PROGRESS;
        startedAt = now;
        participants.stream().filter(KillCompetitionParticipant::isApproved)
                .forEach(participant -> participant.initializeEligibleFrom(now));
        updatedAt = now;
    }

    public void setRecruitmentOpen(boolean open, Instant now) {
        recruitmentOpen = open;
        if (!open) recruitmentClosedAt = now;
        updatedAt = now;
    }

    public void updateEndsAt(Instant endsAt, Instant now) {
        this.endsAt = endsAt;
        this.updatedAt = now;
    }

    public void end(Instant now) {
        endsAt = now;
        recruitmentOpen = false;
        if (recruitmentClosedAt == null) recruitmentClosedAt = now;
        updatedAt = now;
    }

    public void beginInterim(Instant now) { interimCalculationStartedAt = now; updatedAt = now; }
    public void finishInterim(Instant now, Instant latestMatchStartedAt) {
        lastInterimCalculatedAt = now;
        lastInterimMatchStartedAt = latestMatchStartedAt;
        interimCalculationStartedAt = null;
        updatedAt = now;
    }
    public void clearInterimClaim() { interimCalculationStartedAt = null; }
    public void beginFinalization(Instant now, UUID claimToken) {
        finalizationStartedAt = now;
        finalizationClaimToken = Objects.requireNonNull(claimToken);
        resultLastError = null;
        updatedAt = now;
    }
    public boolean ownsFinalizationClaim(UUID claimToken) {
        return claimToken != null && claimToken.equals(finalizationClaimToken);
    }
    public void clearFinalizationClaim() {
        finalizationStartedAt = null;
        finalizationClaimToken = null;
    }
    public void requestResult(Instant now, Instant publishAt) {
        status = KillCompetitionStatus.RESULT_PENDING;
        recruitmentOpen = false;
        resultRequestedAt = now;
        resultPublishAt = publishAt;
        resultLastError = null;
        finalizationStartedAt = null;
        finalizationClaimToken = null;
        updatedAt = now;
    }
    public void recordResultFailure(String message, Instant now) {
        resultLastError = message == null ? "최종 결과 집계에 실패했습니다." : message.substring(0, Math.min(500, message.length()));
        // 실패가 확인된 작업의 소유권은 해제하되 시작 시각은 재시도 backoff 기준으로 유지한다.
        finalizationClaimToken = null;
        updatedAt = now;
    }
    public void complete(Instant now) {
        status = KillCompetitionStatus.COMPLETED;
        completedAt = now;
        finalizationStartedAt = null;
        finalizationClaimToken = null;
        resultLastError = null;
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
    public CommunityGame getCommunityGame() { return communityGame; }
    public CommunityMember getCreatedBy() { return createdBy; }
    public String getTitle() { return title; }
    public KillCompetitionGameMode getGameMode() { return gameMode; }
    public KillCompetitionStatus getStatus() { return status; }
    public Instant getEndsAt() { return endsAt; }
    public Instant getStartedAt() { return startedAt; }
    public int getKillPoint() { return killPoint; }
    public boolean isPlacementPointEnabled() { return placementPointEnabled; }
    public int getFirstPlacePoint() { return firstPlacePoint; }
    public int getSecondPlacePoint() { return secondPlacePoint; }
    public int getThirdPlacePoint() { return thirdPlacePoint; }
    public int getFourthFifthPlacePoint() { return fourthFifthPlacePoint; }
    public int getSixthTenthPlacePoint() { return sixthTenthPlacePoint; }
    public int getFourthPlacePoint() { return fourthPlacePoint == null ? fourthFifthPlacePoint : fourthPlacePoint; }
    public int getFifthPlacePoint() { return fifthPlacePoint == null ? fourthFifthPlacePoint : fifthPlacePoint; }
    public int getSixthPlacePoint() { return sixthPlacePoint == null ? sixthTenthPlacePoint : sixthPlacePoint; }
    public int getSeventhPlacePoint() { return seventhPlacePoint == null ? sixthTenthPlacePoint : seventhPlacePoint; }
    public int getEighthPlacePoint() { return eighthPlacePoint == null ? sixthTenthPlacePoint : eighthPlacePoint; }
    public int getNinthPlacePoint() { return ninthPlacePoint == null ? sixthTenthPlacePoint : ninthPlacePoint; }
    public int getTenthPlacePoint() { return tenthPlacePoint == null ? sixthTenthPlacePoint : tenthPlacePoint; }
    public boolean isRecruitmentOpen() { return recruitmentOpen; }
    public Instant getRecruitmentClosedAt() { return recruitmentClosedAt; }
    public Instant getLastInterimCalculatedAt() { return lastInterimCalculatedAt; }
    public Instant getLastInterimMatchStartedAt() { return lastInterimMatchStartedAt; }
    public Instant getInterimCalculationStartedAt() { return interimCalculationStartedAt; }
    public Instant getFinalizationStartedAt() { return finalizationStartedAt; }
    public UUID getFinalizationClaimToken() { return finalizationClaimToken; }
    public Instant getResultRequestedAt() { return resultRequestedAt; }
    public Instant getResultPublishAt() { return resultPublishAt; }
    public String getResultLastError() { return resultLastError; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<KillCompetitionParticipant> getParticipants() { return participants.stream().toList(); }
    public List<KillCompetitionTeam> getTeams() { return teams.stream().toList(); }
}
