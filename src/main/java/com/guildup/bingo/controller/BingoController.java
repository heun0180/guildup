package com.guildup.bingo.controller;

import com.guildup.bingo.dto.*;
import com.guildup.bingo.service.*;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/communities/{communityId}/games/{communityGameId}/bingos")
public class BingoController {
    private final BingoEventService bingos; private final BingoAggregationService aggregation;
    public BingoController(BingoEventService bingos, BingoAggregationService aggregation) {
        this.bingos = bingos; this.aggregation = aggregation;
    }
    @GetMapping public List<BingoSummaryResponse> list(@PathVariable Long communityId, @PathVariable Long communityGameId, HttpSession session) {
        return bingos.list(CurrentUserSession.requireUserId(session), communityId, communityGameId);
    }
    @GetMapping("/current")
    public BingoCurrentResponse current(@PathVariable Long communityId, @PathVariable Long communityGameId, HttpSession session) {
        return bingos.current(CurrentUserSession.requireUserId(session), communityId, communityGameId);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public BingoDetailResponse create(@PathVariable Long communityId, @PathVariable Long communityGameId, @RequestBody BingoEventRequest request, HttpSession session) {
        return bingos.create(CurrentUserSession.requireUserId(session), communityId, communityGameId, request);
    }
    @GetMapping("/{bingoId:\\d+}")
    public BingoDetailResponse get(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long bingoId, HttpSession session) {
        return bingos.get(CurrentUserSession.requireUserId(session), communityId, communityGameId, bingoId);
    }
    @GetMapping("/{bingoId:\\d+}/me")
    public BingoDetailResponse.PlayerBoard me(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long bingoId, HttpSession session) {
        return bingos.get(CurrentUserSession.requireUserId(session), communityId, communityGameId, bingoId).me();
    }
    @PatchMapping("/{bingoId:\\d+}")
    public BingoDetailResponse update(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long bingoId,
                                      @RequestBody BingoEventRequest request, HttpSession session) {
        return bingos.update(CurrentUserSession.requireUserId(session), communityId, communityGameId, bingoId, request);
    }
    @DeleteMapping("/{bingoId:\\d+}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long bingoId, HttpSession session) {
        bingos.deleteOrCancel(CurrentUserSession.requireUserId(session), communityId, communityGameId, bingoId);
    }
    @PostMapping("/{bingoId:\\d+}/aggregate")
    public BingoAggregationResponse aggregate(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long bingoId, HttpSession session) {
        bingos.get(CurrentUserSession.requireUserId(session), communityId, communityGameId, bingoId);
        return aggregation.aggregate(CurrentUserSession.requireUserId(session), communityId, bingoId);
    }
    @GetMapping("/{bingoId:\\d+}/cells/{cellId:\\d+}/completions")
    public List<BingoCellCompletionResponse> completions(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long bingoId,
            @PathVariable Long cellId, HttpSession session) {
        return bingos.completions(CurrentUserSession.requireUserId(session), communityId, communityGameId, bingoId, cellId);
    }
    @GetMapping("/{bingoId:\\d+}/participants/{participantId:\\d+}")
    public BingoDetailResponse.PlayerBoard participant(@PathVariable Long communityId, @PathVariable Long communityGameId, @PathVariable Long bingoId,
            @PathVariable Long participantId, HttpSession session) {
        return bingos.participantBoard(CurrentUserSession.requireUserId(session), communityId, communityGameId, bingoId, participantId);
    }
}
