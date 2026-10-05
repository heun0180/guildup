package com.guildup.killcompetition.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "pubg_kill_competition_teams", uniqueConstraints = @UniqueConstraint(
        name = "uk_pubg_kill_competition_team_order", columnNames = {"competition_id", "display_order"}
))
public class KillCompetitionTeam {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private KillCompetition competition;
    @Column(name = "team_name", nullable = false, length = 40) private String teamName;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    // NULL은 기존 DB의 팀 점수 정책 전환 대상임을 나타낸다. 신규 팀은 0부터 시작한다.
    @Column(name = "interim_placement_points") private Integer interimPlacementPoints = 0;
    @Column(name = "final_placement_points") private Integer finalPlacementPoints;
    protected KillCompetitionTeam() {}
    public KillCompetitionTeam(KillCompetition competition, String teamName, int displayOrder) {
        this.competition = competition; this.teamName = teamName; this.displayOrder = displayOrder;
    }
    public Long getId() { return id; }
    public String getTeamName() { return teamName; }
    public int getDisplayOrder() { return displayOrder; }
    public int getInterimPlacementPoints() { return interimPlacementPoints == null ? 0 : interimPlacementPoints; }
    public Integer getFinalPlacementPoints() { return finalPlacementPoints; }
    public void recordInterimPlacementPoints(int points) { interimPlacementPoints = points; }
    public void recordFinalPlacementPoints(int points) { finalPlacementPoints = points; }
}
