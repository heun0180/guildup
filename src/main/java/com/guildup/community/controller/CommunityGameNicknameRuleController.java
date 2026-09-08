package com.guildup.community.controller;

import com.guildup.community.dto.GameNicknameRulePreviewResponse;
import com.guildup.community.dto.GameNicknameRuleRequest;
import com.guildup.community.dto.GameNicknameRuleResponse;
import com.guildup.community.dto.GameNicknameRuleStatusResponse;
import com.guildup.community.service.CommunityGameNicknameRuleService;
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
@RequestMapping("/api/communities/{communityId}/game-nickname-rule")
public class CommunityGameNicknameRuleController {

    private final CommunityGameNicknameRuleService ruleService;

    public CommunityGameNicknameRuleController(CommunityGameNicknameRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    public GameNicknameRuleResponse getRule(@PathVariable Long communityId, HttpSession session) {
        return ruleService.getRule(CurrentUserSession.requireUserId(session), communityId);
    }

    @GetMapping("/status")
    public GameNicknameRuleStatusResponse getStatus(@PathVariable Long communityId, HttpSession session) {
        return ruleService.getStatus(CurrentUserSession.requireUserId(session), communityId);
    }

    @PostMapping("/preview")
    public GameNicknameRulePreviewResponse preview(
            @PathVariable Long communityId,
            @RequestBody GameNicknameRuleRequest request,
            HttpSession session
    ) {
        return ruleService.preview(
                CurrentUserSession.requireUserId(session), communityId, request.gameNickname()
        );
    }

    @GetMapping("/preview")
    public GameNicknameRulePreviewResponse previewSavedRule(
            @PathVariable Long communityId,
            HttpSession session
    ) {
        return ruleService.previewSavedRule(
                CurrentUserSession.requireUserId(session), communityId
        );
    }

    @PutMapping
    public GameNicknameRuleResponse save(
            @PathVariable Long communityId,
            @RequestBody GameNicknameRuleRequest request,
            HttpSession session
    ) {
        return ruleService.save(
                CurrentUserSession.requireUserId(session), communityId, request.gameNickname()
        );
    }
}
