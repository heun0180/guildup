package com.guildup.community.controller;

import com.guildup.community.dto.CommunityEventRequest;
import com.guildup.community.dto.CommunityEventResponse;
import com.guildup.community.service.CommunityEventService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/communities/{communityId}/events")
public class CommunityEventController {
    private final CommunityEventService service;

    public CommunityEventController(CommunityEventService service) { this.service = service; }

    @GetMapping
    public List<CommunityEventResponse> list(@PathVariable Long communityId, HttpSession session) {
        return service.list(CurrentUserSession.requireUserId(session), communityId);
    }

    @GetMapping("/{id}")
    public CommunityEventResponse get(@PathVariable Long communityId, @PathVariable Long id, HttpSession session) {
        return service.get(CurrentUserSession.requireUserId(session), communityId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityEventResponse create(@PathVariable Long communityId,
                                          @RequestBody CommunityEventRequest request, HttpSession session) {
        return service.create(CurrentUserSession.requireUserId(session), communityId, request);
    }

    @PutMapping("/{id}")
    public CommunityEventResponse update(@PathVariable Long communityId, @PathVariable Long id,
                                          @RequestBody CommunityEventRequest request, HttpSession session) {
        return service.update(CurrentUserSession.requireUserId(session), communityId, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long communityId, @PathVariable Long id, HttpSession session) {
        service.delete(CurrentUserSession.requireUserId(session), communityId, id);
    }
}
