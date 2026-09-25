package com.guildup.killcompetition.controller;

import com.guildup.killcompetition.dto.*;
import com.guildup.killcompetition.service.*;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/communities/{communityId}/games/{communityGameId}/kill-competitions")
public class KillCompetitionController {
    private final KillCompetitionService competitions;
    private final KillCompetitionSettlementService settlements;
    private final KillCompetitionParticipationService participation;
    public KillCompetitionController(KillCompetitionService competitions, KillCompetitionSettlementService settlements,
                                     KillCompetitionParticipationService participation) {
        this.competitions = competitions; this.settlements = settlements; this.participation = participation;
    }
    @GetMapping public List<KillCompetitionSummaryResponse> list(@PathVariable Long communityId, @PathVariable Long communityGameId, HttpSession session) {
        return competitions.list(CurrentUserSession.requireUserId(session), communityId, communityGameId);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public KillCompetitionDetailResponse create(@PathVariable Long communityId, @PathVariable Long communityGameId,
                                                 @RequestBody KillCompetitionCreateRequest request, HttpSession session) {
        return competitions.create(CurrentUserSession.requireUserId(session), communityId, communityGameId, request);
    }
    @GetMapping("/{competitionId}")
    public KillCompetitionDetailResponse get(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.get(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
    }
    @PostMapping("/{competitionId}/participants/me")
    public KillCompetitionDetailResponse join(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId, HttpSession session) {
        competitions.get(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
        return participation.join(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @DeleteMapping("/{competitionId}/participants/me")
    public KillCompetitionDetailResponse leave(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.leave(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
    }
    @PutMapping("/{competitionId}/recruitment")
    public KillCompetitionDetailResponse recruitment(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId,
                                                      @RequestBody KillCompetitionRecruitmentRequest request, HttpSession session) {
        return competitions.setRecruitment(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId, request.open());
    }
    @PostMapping("/{competitionId}/participants")
    public KillCompetitionDetailResponse addParticipant(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId,
                                                        @RequestBody KillCompetitionParticipantAddRequest request, HttpSession session) {
        competitions.get(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
        return participation.addMember(CurrentUserSession.requireUserId(session), communityId, competitionId,
                request.memberId(), request.teamId());
    }
    @PostMapping("/{competitionId}/participants/{participantId}/approve")
    public KillCompetitionDetailResponse approve(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId,
                                                  @PathVariable Long participantId,
                                                  @RequestBody(required = false) KillCompetitionParticipantApprovalRequest request,
                                                  HttpSession session) {
        return competitions.approveParticipant(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId,
                participantId, request == null ? null : request.teamId());
    }
    @PostMapping("/{competitionId}/participants/{participantId}/reject")
    public KillCompetitionDetailResponse reject(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId,
                                                 @PathVariable Long participantId, HttpSession session) {
        return competitions.rejectParticipant(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId, participantId);
    }
    @DeleteMapping("/{competitionId}/participants/{participantId}")
    public KillCompetitionDetailResponse removeParticipant(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId,
                                                           @PathVariable Long participantId, HttpSession session) {
        return competitions.removeParticipant(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId, participantId);
    }
    @PutMapping("/{competitionId}/participants/{participantId}/team")
    public KillCompetitionDetailResponse team(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId,
                                               @PathVariable Long participantId,
                                               @RequestBody KillCompetitionParticipantTeamRequest request, HttpSession session) {
        return competitions.changeParticipantTeam(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId,
                participantId, request.teamId());
    }
    @PostMapping("/{competitionId}/close-recruitment")
    public KillCompetitionDetailResponse close(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.closeRecruitment(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
    }
    @PutMapping("/{competitionId}/teams")
    public KillCompetitionDetailResponse teams(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId,
                                               @RequestBody KillCompetitionTeamRequest request, HttpSession session) {
        return competitions.configureTeams(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId, request);
    }
    @PostMapping("/{competitionId}/start")
    public KillCompetitionDetailResponse start(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.start(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
    }
    @PostMapping("/{competitionId}/end")
    public KillCompetitionDetailResponse end(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.end(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
    }
    @PutMapping("/{competitionId}/ends-at")
    public KillCompetitionDetailResponse updateEndTime(@PathVariable Long communityId, @PathVariable Long communityGameId,
                                                       @PathVariable Long competitionId,
                                                       @RequestBody KillCompetitionEndTimeRequest request,
                                                       HttpSession session) {
        return competitions.updateEndTime(CurrentUserSession.requireUserId(session), communityId, communityGameId,
                competitionId, request == null ? null : request.endsAt());
    }
    @PostMapping("/{competitionId}/interim")
    public KillCompetitionDetailResponse interim(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId, HttpSession session) {
        competitions.get(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
        return settlements.calculateInterim(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @PostMapping({"/{competitionId}/result-request", "/{competitionId}/finalize"})
    public KillCompetitionDetailResponse finalizeResult(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId, HttpSession session) {
        competitions.get(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
        return settlements.finalizeResult(CurrentUserSession.requireUserId(session), communityId, competitionId);
    }
    @PostMapping("/{competitionId}/cancel")
    public KillCompetitionDetailResponse cancel(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long competitionId, HttpSession session) {
        return competitions.cancel(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
    }
    @DeleteMapping("/{competitionId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long communityId, @PathVariable Long communityGameId,
                       @PathVariable Long competitionId, HttpSession session) {
        competitions.delete(CurrentUserSession.requireUserId(session), communityId, communityGameId, competitionId);
    }
}
