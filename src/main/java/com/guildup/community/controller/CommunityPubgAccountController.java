package com.guildup.community.controller;

import com.guildup.community.service.CommunityPubgAccountService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/communities/{communityId}/games/{communityGameId}/pubg-account/me")
public class CommunityPubgAccountController {
    private final CommunityPubgAccountService accounts;
    public CommunityPubgAccountController(CommunityPubgAccountService accounts) { this.accounts = accounts; }

    @GetMapping
    public CommunityPubgAccountService.AccountResponse get(@PathVariable Long communityId,
            @PathVariable Long communityGameId, HttpSession session) {
        return accounts.get(CurrentUserSession.requireUserId(session), communityId, communityGameId);
    }

    @PutMapping
    public CommunityPubgAccountService.AccountResponse save(@PathVariable Long communityId,
            @PathVariable Long communityGameId, @RequestBody AccountRequest request, HttpSession session) {
        return accounts.save(CurrentUserSession.requireUserId(session), communityId, communityGameId, request.nickname());
    }

    public record AccountRequest(String nickname) { }
}
