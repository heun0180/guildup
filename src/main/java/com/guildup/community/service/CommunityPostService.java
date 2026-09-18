package com.guildup.community.service;

import com.guildup.community.domain.*;
import com.guildup.community.dto.*;
import com.guildup.community.repository.CommunityPostCommentRepository;
import com.guildup.community.repository.CommunityPostRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CommunityPostService {
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_CONTENT_LENGTH = 20_000;
    private static final int MAX_COMMENT_LENGTH = 2_000;

    private final CommunityAccessService access;
    private final CommunityPostRepository posts;
    private final CommunityPostCommentRepository comments;
    private final Clock clock;

    public CommunityPostService(CommunityAccessService access, CommunityPostRepository posts,
                                CommunityPostCommentRepository comments, Clock clock) {
        this.access = access;
        this.posts = posts;
        this.comments = comments;
        this.clock = clock;
    }

    public CommunityPostPageResponse list(Long userId, Long communityId, CommunityPostCategory category,
                                          int page, int size) {
        access.requireCommunityMember(userId, communityId);
        if (page < 0 || size < 1 || size > 50) badRequest("페이지는 0 이상, 크기는 1~50이어야 합니다.");
        var result = posts.findPage(communityId, category, PageRequest.of(page, size));
        var ids = result.getContent().stream().map(CommunityPost::getId).toList();
        Map<Long, Long> counts = ids.isEmpty() ? Collections.emptyMap() : posts.countComments(ids).stream()
                .collect(Collectors.toMap(CommunityPostRepository.CommentCount::getPostId,
                        CommunityPostRepository.CommentCount::getCommentCount));
        var content = result.getContent().stream()
                .map(post -> CommunityPostListItemResponse.from(post, counts.getOrDefault(post.getId(), 0L)))
                .toList();
        return CommunityPostPageResponse.from(result, content);
    }

    @Transactional
    public CommunityPostDetailResponse get(Long userId, Long communityId, Long postId, boolean incrementView) {
        CommunityUser viewer = access.requireCommunityMember(userId, communityId);
        CommunityPost post = requirePost(communityId, postId);
        if (incrementView) {
            posts.incrementViewCount(communityId, postId);
            post = requirePost(communityId, postId);
        }
        return detail(post, viewer);
    }

    @Transactional
    public CommunityPostDetailResponse create(Long userId, Long communityId, CommunityPostCreateRequest request) {
        CommunityUser author = access.requireCommunityMember(userId, communityId);
        requireRequest(request);
        boolean notice = Boolean.TRUE.equals(request.notice());
        boolean pinned = Boolean.TRUE.equals(request.pinned());
        if ((notice || pinned) && !isManager(author)) forbidden("운영진만 공지 또는 상단 고정을 지정할 수 있습니다.");
        CommunityPost post = posts.save(new CommunityPost(author.getCommunity(), author,
                requireCategory(request.category()), title(request.title()), content(request.content()),
                notice, pinned, clock.instant()));
        return detail(post, author);
    }

    @Transactional
    public CommunityPostDetailResponse update(Long userId, Long communityId, Long postId,
                                               CommunityPostUpdateRequest request) {
        CommunityUser viewer = access.requireCommunityMember(userId, communityId);
        CommunityPost post = requirePost(communityId, postId);
        if (request == null) badRequest("입력 내용을 확인해 주세요.");
        boolean contentUpdate = request.category() != null || request.title() != null || request.content() != null;
        boolean managementUpdate = request.notice() != null || request.pinned() != null;
        if (!contentUpdate && !managementUpdate) badRequest("변경할 내용이 없습니다.");
        if (contentUpdate) {
            if (!isAuthor(viewer, post)) forbidden("작성자만 게시글 내용을 수정할 수 있습니다.");
            post.updateContent(requireCategory(request.category()), title(request.title()),
                    content(request.content()), clock.instant());
        }
        if (managementUpdate) {
            if (!isManager(viewer)) forbidden("운영진만 공지 또는 상단 고정을 변경할 수 있습니다.");
            post.updateManagement(request.notice() == null ? post.isNotice() : request.notice(),
                    request.pinned() == null ? post.isPinned() : request.pinned(), clock.instant());
        }
        return detail(post, viewer);
    }

    @Transactional
    public void delete(Long userId, Long communityId, Long postId) {
        CommunityUser viewer = access.requireCommunityMember(userId, communityId);
        CommunityPost post = requirePost(communityId, postId);
        if (!isAuthor(viewer, post) && !isManager(viewer)) forbidden("게시글을 삭제할 권한이 없습니다.");
        post.delete(clock.instant());
    }

    @Transactional
    public CommunityPostCommentResponse createComment(Long userId, Long communityId, Long postId,
                                                       CommunityPostCommentRequest request) {
        CommunityUser author = access.requireCommunityMember(userId, communityId);
        CommunityPost post = requirePost(communityId, postId);
        String value = commentContent(request);
        return CommunityPostCommentResponse.from(
                comments.save(new CommunityPostComment(post, author, value, clock.instant())), author);
    }

    @Transactional
    public CommunityPostCommentResponse updateComment(Long userId, Long communityId, Long postId, Long commentId,
                                                       CommunityPostCommentRequest request) {
        CommunityUser viewer = access.requireCommunityMember(userId, communityId);
        CommunityPostComment comment = requireComment(communityId, postId, commentId);
        if (!comment.getAuthorCommunityUser().getId().equals(viewer.getId())) {
            forbidden("작성자만 댓글을 수정할 수 있습니다.");
        }
        comment.update(commentContent(request), clock.instant());
        return CommunityPostCommentResponse.from(comment, viewer);
    }

    @Transactional
    public void deleteComment(Long userId, Long communityId, Long postId, Long commentId) {
        CommunityUser viewer = access.requireCommunityMember(userId, communityId);
        CommunityPostComment comment = requireComment(communityId, postId, commentId);
        if (!comment.getAuthorCommunityUser().getId().equals(viewer.getId()) && !isManager(viewer)) {
            forbidden("댓글을 삭제할 권한이 없습니다.");
        }
        comment.delete(clock.instant());
    }

    private CommunityPostDetailResponse detail(CommunityPost post, CommunityUser viewer) {
        var responses = comments.findActiveByPost(post.getCommunity().getId(), post.getId()).stream()
                .map(comment -> CommunityPostCommentResponse.from(comment, viewer)).toList();
        return CommunityPostDetailResponse.from(post, viewer, responses);
    }

    private CommunityPost requirePost(Long communityId, Long postId) {
        return posts.findActive(communityId, postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."));
    }

    private CommunityPostComment requireComment(Long communityId, Long postId, Long commentId) {
        return comments.findActive(communityId, postId, commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."));
    }

    private boolean isAuthor(CommunityUser viewer, CommunityPost post) {
        return post.getAuthorCommunityUser().getId().equals(viewer.getId());
    }

    private boolean isManager(CommunityUser member) {
        return member.getRole() == CommunityUserRole.OWNER || member.getRole() == CommunityUserRole.ADMIN;
    }

    private CommunityPostCategory requireCategory(CommunityPostCategory category) {
        if (category == null) badRequest("카테고리를 선택해 주세요.");
        return category;
    }

    private String title(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 2 || normalized.length() > MAX_TITLE_LENGTH) {
            badRequest("제목은 2~200자로 입력해 주세요.");
        }
        return normalized;
    }

    private String content(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 2 || normalized.length() > MAX_CONTENT_LENGTH) {
            badRequest("본문은 2~20,000자로 입력해 주세요.");
        }
        return normalized;
    }

    private String commentContent(CommunityPostCommentRequest request) {
        String normalized = request == null || request.content() == null ? "" : request.content().trim();
        if (normalized.isEmpty() || normalized.length() > MAX_COMMENT_LENGTH) {
            badRequest("댓글은 1~2,000자로 입력해 주세요.");
        }
        return normalized;
    }

    private void requireRequest(CommunityPostCreateRequest request) {
        if (request == null) badRequest("입력 내용을 확인해 주세요.");
    }

    private void badRequest(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private void forbidden(String message) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
}
