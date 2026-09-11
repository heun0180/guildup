package com.guildup.community.controller;

import com.guildup.community.dto.CommunityNoticeRequest;
import com.guildup.community.dto.CommunityNoticeResponse;
import com.guildup.community.service.CommunityNoticeService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/communities/{communityId}/notices")
public class CommunityNoticeController {
    private final CommunityNoticeService service;

    public CommunityNoticeController(CommunityNoticeService service) { this.service = service; }

    @GetMapping
    public List<CommunityNoticeResponse> list(@PathVariable Long communityId, HttpSession session) {
        return service.list(CurrentUserSession.requireUserId(session), communityId);
    }

    @GetMapping("/{id}")
    public CommunityNoticeResponse get(@PathVariable Long communityId, @PathVariable Long id, HttpSession session) {
        return service.get(CurrentUserSession.requireUserId(session), communityId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityNoticeResponse create(@PathVariable Long communityId,
                                          @RequestBody CommunityNoticeRequest request, HttpSession session) {
        return service.create(CurrentUserSession.requireUserId(session), communityId, request);
    }

    @PutMapping("/{id}")
    public CommunityNoticeResponse update(@PathVariable Long communityId, @PathVariable Long id,
                                          @RequestBody CommunityNoticeRequest request, HttpSession session) {
        return service.update(CurrentUserSession.requireUserId(session), communityId, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long communityId, @PathVariable Long id, HttpSession session) {
        service.delete(CurrentUserSession.requireUserId(session), communityId, id);
    }
}
