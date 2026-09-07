package com.guildup.community.controller;

import com.guildup.community.domain.CommunityMember;
import com.guildup.community.dto.CommunityMemberCreateRequest;
import com.guildup.community.dto.CommunityMemberResponse;
import com.guildup.community.service.CommunityMemberService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** GuildUp이 자체 관리하는 커뮤니티 클랜원의 수동 등록과 목록 조회 API를 제공한다. */
@RestController
@RequestMapping("/api/communities/{communityId}/members")
public class CommunityMemberController {

    private final CommunityMemberService communityMemberService;

    public CommunityMemberController(CommunityMemberService communityMemberService) {
        this.communityMemberService = communityMemberService;
    }

    /** 닉네임으로 클랜원을 직접 추가하고 201 Created를 반환한다. */
    @PostMapping
    public ResponseEntity<CommunityMemberResponse> addMember(
            @PathVariable Long communityId,
            @RequestBody CommunityMemberCreateRequest request
    ) {
        CommunityMember member = communityMemberService.addMember(communityId, request.nickname());
        return ResponseEntity.status(HttpStatus.CREATED).body(CommunityMemberResponse.from(member));
    }

    /** 등록 순서대로 커뮤니티의 전체 클랜원을 조회한다. */
    @GetMapping
    public List<CommunityMemberResponse> getMembers(@PathVariable Long communityId) {
        return communityMemberService.getMembers(communityId).stream()
                .map(CommunityMemberResponse::from)
                .toList();
    }
}
