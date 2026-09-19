package com.guildup.community.controller;

import com.guildup.community.dto.GameNicknameRulePreviewResponse;
import com.guildup.community.dto.GameNicknameRuleRequest;
import com.guildup.community.dto.GameNicknameRuleResponse;
import com.guildup.community.dto.GameNicknameRuleStatusResponse;
import com.guildup.community.dto.CommunityGameNicknameSyncResponse;
import com.guildup.community.service.CommunityGameNicknameRuleService;
import com.guildup.community.service.CommunityGameNicknameSyncService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 커뮤니티의 인게임 닉네임 추출 규칙 조회, 미리보기와 저장 API다. */
@RestController
@RequestMapping("/api/communities/{communityId}/games/{communityGameId}/nickname-rule")
public class CommunityGameNicknameRuleController {

    private final CommunityGameNicknameRuleService ruleService;
    private final CommunityGameNicknameSyncService syncService;

    public CommunityGameNicknameRuleController(
            CommunityGameNicknameRuleService ruleService,
            CommunityGameNicknameSyncService syncService
    ) {
        this.ruleService = ruleService;
        this.syncService = syncService;
    }

    @GetMapping
    public GameNicknameRuleResponse getRule(@PathVariable Long communityId, @PathVariable Long communityGameId, HttpSession session) {
        return ruleService.getRule(CurrentUserSession.requireUserId(session), communityId, communityGameId);
    }

    @GetMapping("/status")
    public GameNicknameRuleStatusResponse getStatus(@PathVariable Long communityId, @PathVariable Long communityGameId, HttpSession session) {
        return ruleService.getStatus(CurrentUserSession.requireUserId(session), communityId, communityGameId);
    }

    @PostMapping("/preview")
    public GameNicknameRulePreviewResponse preview(
            @PathVariable Long communityId,
            @PathVariable Long communityGameId,
            @RequestBody GameNicknameRuleRequest request,
            HttpSession session
    ) {
        return ruleService.preview(
                CurrentUserSession.requireUserId(session), communityId, communityGameId, request.gameNickname()
        );
    }

    @GetMapping("/preview")
    public GameNicknameRulePreviewResponse previewSavedRule(
            @PathVariable Long communityId,
            @PathVariable Long communityGameId,
            HttpSession session
    ) {
        return ruleService.previewSavedRule(
                CurrentUserSession.requireUserId(session), communityId, communityGameId
        );
    }

    @PutMapping
    public GameNicknameRuleResponse save(
            @PathVariable Long communityId,
            @PathVariable Long communityGameId,
            @RequestBody GameNicknameRuleRequest request,
            HttpSession session
    ) {
        return ruleService.save(
                CurrentUserSession.requireUserId(session), communityId, communityGameId, request.gameNickname()
        );
    }

    @PostMapping("/sync")
    public CommunityGameNicknameSyncResponse synchronize(
            @PathVariable Long communityId,
            @PathVariable Long communityGameId,
            HttpSession session
    ) {
        return syncService.synchronize(
                CurrentUserSession.requireUserId(session), communityId, communityGameId
        );
    }
}
