package com.guildup.community.controller;

import com.guildup.community.domain.CommunityPostCategory;
import com.guildup.community.dto.*;
import com.guildup.community.service.CommunityPostService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/communities/{communityId}/posts")
public class CommunityPostController {
    private static final String VIEW_TOKENS = CommunityPostController.class.getName() + ".VIEW_TOKENS";
    private static final int MAX_VIEW_TOKENS_PER_SESSION = 200;

    private final CommunityPostService service;

    public CommunityPostController(CommunityPostService service) { this.service = service; }

    @GetMapping
    public CommunityPostPageResponse list(@PathVariable Long communityId,
                                          @RequestParam(required = false) CommunityPostCategory category,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          HttpSession session) {
        return service.list(CurrentUserSession.requireUserId(session), communityId, category, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityPostDetailResponse create(@PathVariable Long communityId,
                                              @RequestBody CommunityPostCreateRequest request,
                                              HttpSession session) {
        return service.create(CurrentUserSession.requireUserId(session), communityId, request);
    }

    @GetMapping("/{postId}")
    public CommunityPostDetailResponse get(@PathVariable Long communityId, @PathVariable Long postId,
                                           @RequestHeader(name = "X-View-Token", required = false) String viewToken,
                                           HttpSession session) {
        Long userId = CurrentUserSession.requireUserId(session);
        if (viewToken == null || viewToken.isBlank() || viewToken.length() > 100) {
            return service.get(userId, communityId, postId, true);
        }
        synchronized (session) {
            return service.get(userId, communityId, postId,
                    rememberFirstView(session, communityId + ":" + postId + ":" + viewToken));
        }
    }

    @PatchMapping("/{postId}")
    public CommunityPostDetailResponse update(@PathVariable Long communityId, @PathVariable Long postId,
                                              @RequestBody CommunityPostUpdateRequest request,
                                              HttpSession session) {
        return service.update(CurrentUserSession.requireUserId(session), communityId, postId, request);
    }

    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long communityId, @PathVariable Long postId, HttpSession session) {
        service.delete(CurrentUserSession.requireUserId(session), communityId, postId);
    }

    @PostMapping("/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityPostCommentResponse createComment(@PathVariable Long communityId, @PathVariable Long postId,
                                                       @RequestBody CommunityPostCommentRequest request,
                                                       HttpSession session) {
        return service.createComment(CurrentUserSession.requireUserId(session), communityId, postId, request);
    }

    @PatchMapping("/{postId}/comments/{commentId}")
    public CommunityPostCommentResponse updateComment(@PathVariable Long communityId, @PathVariable Long postId,
                                                       @PathVariable Long commentId,
                                                       @RequestBody CommunityPostCommentRequest request,
                                                       HttpSession session) {
        return service.updateComment(CurrentUserSession.requireUserId(session), communityId, postId, commentId, request);
    }

    @DeleteMapping("/{postId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@PathVariable Long communityId, @PathVariable Long postId,
                              @PathVariable Long commentId, HttpSession session) {
        service.deleteComment(CurrentUserSession.requireUserId(session), communityId, postId, commentId);
    }

    @SuppressWarnings("unchecked")
    private boolean rememberFirstView(HttpSession session, String key) {
        Set<String> tokens = (Set<String>) session.getAttribute(VIEW_TOKENS);
        if (tokens == null) {
            tokens = new LinkedHashSet<>();
            session.setAttribute(VIEW_TOKENS, tokens);
        }
        if (!tokens.add(key)) return false;
        if (tokens.size() > MAX_VIEW_TOKENS_PER_SESSION) {
            tokens.remove(tokens.iterator().next());
        }
        return true;
    }
}
