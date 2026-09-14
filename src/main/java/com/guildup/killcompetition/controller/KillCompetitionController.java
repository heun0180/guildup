package com.guildup.killcompetition.controller;

import com.guildup.killcompetition.dto.*;
import com.guildup.killcompetition.service.*;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/communities/{communityId}/kill-competitions")
public class KillCompetitionController {
    private final KillCompetitionService competitions;
    private final KillCompetitionSettlementService settlements;
    private final KillCompetitionParticipationService participation;
    public KillCompetitionController(KillCompetitionService competitions, KillCompetitionSettlementService settlements,
                                     KillCompetitionParticipationService participation) {
        this.competitions = competitions; this.settlements = settlements; this.participation = participation;
    }
    @GetMapping public List<KillCompetitionSummaryResponse> list(@PathVariable Long communityId, HttpSession session) {
        return competitions.list(CurrentUserSession.requireUserId(session), communityId);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public KillCompetitionDetailResponse create(@PathVariable Long communityId,
                                                 @RequestBody KillCompetitionCreateRequest request, HttpSession session) {
        return competitions.create(CurrentUserSession.requireUserId(session), communityId, request);
    }
    @GetMapping("/{competitionId}")
    public KillCompetitionDetailResponse get(@PathVariable Long communityId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.get(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @PostMapping("/{competitionId}/participants/me")
    public KillCompetitionDetailResponse join(@PathVariable Long communityId, @PathVariable Long competitionId, HttpSession session) {
        return participation.join(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @DeleteMapping("/{competitionId}/participants/me")
    public KillCompetitionDetailResponse leave(@PathVariable Long communityId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.leave(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @PostMapping("/{competitionId}/close-recruitment")
    public KillCompetitionDetailResponse close(@PathVariable Long communityId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.closeRecruitment(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @PutMapping("/{competitionId}/teams")
    public KillCompetitionDetailResponse teams(@PathVariable Long communityId, @PathVariable Long competitionId,
                                               @RequestBody KillCompetitionTeamRequest request, HttpSession session) {
        return competitions.configureTeams(CurrentUserSession.requireUserId(session), communityId, competitionId, request);
    }
    @PostMapping("/{competitionId}/start")
    public KillCompetitionDetailResponse start(@PathVariable Long communityId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.start(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @PostMapping("/{competitionId}/interim")
    public KillCompetitionDetailResponse interim(@PathVariable Long communityId, @PathVariable Long competitionId, HttpSession session) {
        return settlements.calculateInterim(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @PostMapping("/{competitionId}/finalize")
    public KillCompetitionDetailResponse finalizeResult(@PathVariable Long communityId, @PathVariable Long competitionId, HttpSession session) {
        return settlements.finalizeResult(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @PostMapping("/{competitionId}/cancel")
    public KillCompetitionDetailResponse cancel(@PathVariable Long communityId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.cancel(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @DeleteMapping("/{competitionId}")
    public KillCompetitionDetailResponse deactivate(@PathVariable Long communityId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.cancel(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
}
