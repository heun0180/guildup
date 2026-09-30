package com.guildup.developer.controller;

import com.guildup.developer.dto.DeveloperResponses;
import com.guildup.developer.service.DeveloperQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/developer")
public class DeveloperController {
    private final DeveloperQueryService queries;

    public DeveloperController(DeveloperQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/dashboard")
    public DeveloperResponses.Dashboard dashboard() {
        return queries.dashboard();
    }

    @GetMapping("/search")
    public DeveloperResponses.SearchResponse search(@RequestParam(defaultValue = "") String q,
                                                     @RequestParam(defaultValue = "12") int limit) {
        return queries.search(q, limit);
    }

    @GetMapping("/communities")
    public DeveloperResponses.Page<DeveloperResponses.CommunitySummary> communities(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.communities(q, page, size);
    }

    @GetMapping("/communities/{communityId}")
    public DeveloperResponses.CommunityDetail community(@PathVariable long communityId) {
        return queries.community(communityId);
    }

    @GetMapping("/communities/{communityId}/users")
    public DeveloperResponses.Page<DeveloperResponses.CommunityUser> communityUsers(
            @PathVariable long communityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.communityUsers(communityId, page, size);
    }

    @GetMapping("/communities/{communityId}/members")
    public DeveloperResponses.Page<DeveloperResponses.CommunityMember> communityMembers(
            @PathVariable long communityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.communityMembers(communityId, page, size);
    }

    @GetMapping("/communities/{communityId}/bingos")
    public DeveloperResponses.Page<DeveloperResponses.BingoSummary> bingos(
            @PathVariable long communityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.bingos(communityId, page, size);
    }

    @GetMapping("/communities/{communityId}/bingos/{bingoId}")
    public DeveloperResponses.BingoDetail bingo(@PathVariable long communityId, @PathVariable long bingoId) {
        return queries.bingo(communityId, bingoId);
    }

    @GetMapping("/communities/{communityId}/bingos/{bingoId}/participants")
    public DeveloperResponses.Page<DeveloperResponses.BingoParticipant> bingoParticipants(
            @PathVariable long communityId, @PathVariable long bingoId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.bingoParticipants(communityId, bingoId, page, size);
    }

    @GetMapping("/communities/{communityId}/bingos/{bingoId}/processed-matches")
    public DeveloperResponses.Page<DeveloperResponses.BingoProcessedMatch> bingoProcessedMatches(
            @PathVariable long communityId, @PathVariable long bingoId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.bingoProcessedMatches(communityId, bingoId, page, size);
    }

    @GetMapping("/communities/{communityId}/kill-competitions")
    public DeveloperResponses.Page<DeveloperResponses.KillCompetitionSummary> killCompetitions(
            @PathVariable long communityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.killCompetitions(communityId, page, size);
    }

    @GetMapping("/communities/{communityId}/kill-competitions/{competitionId}")
    public DeveloperResponses.KillCompetitionDetail killCompetition(
            @PathVariable long communityId, @PathVariable long competitionId) {
        return queries.killCompetition(communityId, competitionId);
    }

    @GetMapping("/communities/{communityId}/kill-competitions/{competitionId}/participants")
    public DeveloperResponses.Page<DeveloperResponses.KillCompetitionParticipant> killCompetitionParticipants(
            @PathVariable long communityId, @PathVariable long competitionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.killCompetitionParticipants(communityId, competitionId, page, size);
    }

    @GetMapping("/communities/{communityId}/kill-competitions/{competitionId}/matches")
    public DeveloperResponses.Page<DeveloperResponses.KillCompetitionMatchResult> killCompetitionMatches(
            @PathVariable long communityId, @PathVariable long competitionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.killCompetitionMatches(communityId, competitionId, page, size);
    }
}
