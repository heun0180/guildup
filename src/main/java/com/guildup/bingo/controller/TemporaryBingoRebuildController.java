package com.guildup.bingo.controller;

import com.guildup.bingo.dto.*;
import com.guildup.bingo.service.TemporaryBingoRebuildService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

/** TEMPORARY: 2026-09 bingo progress repair tool. Remove after current event verification. */
@RestController
@RequestMapping("/api/communities/{communityId}/games/{communityGameId}/bingos/temporary-rebuild")
public class TemporaryBingoRebuildController {
    private final TemporaryBingoRebuildService rebuild;

    public TemporaryBingoRebuildController(TemporaryBingoRebuildService rebuild) {
        this.rebuild = rebuild;
    }

    @PostMapping("/preview")
    public TemporaryBingoRebuildResponse preview(@PathVariable Long communityId,
                                                  @PathVariable Long communityGameId,
                                                  HttpSession session) {
        return rebuild.preview(CurrentUserSession.requireUserId(session), communityId, communityGameId);
    }

    @PostMapping("/apply")
    public TemporaryBingoRebuildResponse apply(@PathVariable Long communityId,
                                                @PathVariable Long communityGameId,
                                                @RequestBody TemporaryBingoRebuildApplyRequest request,
                                                HttpSession session) {
        return rebuild.apply(CurrentUserSession.requireUserId(session), communityId, communityGameId,
                request.previewToken());
    }
}
