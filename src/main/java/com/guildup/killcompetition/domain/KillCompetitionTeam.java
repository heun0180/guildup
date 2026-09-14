package com.guildup.killcompetition.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "kill_competition_teams", uniqueConstraints = @UniqueConstraint(
        name = "uk_kill_competition_team_order", columnNames = {"competition_id", "display_order"}
))
public class KillCompetitionTeam {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private KillCompetition competition;
    @Column(name = "team_name", nullable = false, length = 40) private String teamName;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    protected KillCompetitionTeam() {}
    public KillCompetitionTeam(KillCompetition competition, String teamName, int displayOrder) {
        this.competition = competition; this.teamName = teamName; this.displayOrder = displayOrder;
    }
    public Long getId() { return id; }
    public String getTeamName() { return teamName; }
    public int getDisplayOrder() { return displayOrder; }
}
