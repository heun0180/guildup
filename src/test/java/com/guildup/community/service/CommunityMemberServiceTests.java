package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityMemberServiceTests {

    private final CommunityMemberRepository communityMemberRepository = mock(CommunityMemberRepository.class);
    private final CommunityMemberAccountRepository accountRepository = mock(CommunityMemberAccountRepository.class);
    private final CommunityAccessService accessService = mock(CommunityAccessService.class);
    private final CommunityMemberService communityMemberService =
            new CommunityMemberService(communityMemberRepository, accountRepository, accessService);

    @Test
    void addsManualMemberWithoutExternalAccountData() {
        Community community = new Community("GuildUp 클랜");
        var membership = mock(com.guildup.community.domain.CommunityUser.class);
        when(membership.getCommunity()).thenReturn(community);
        when(accessService.requireManagementAccess(10L, 1L)).thenReturn(membership);
        when(communityMemberRepository.save(org.mockito.ArgumentMatchers.any(CommunityMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommunityMember result = communityMemberService.addMember(10L, 1L, "애플");

        ArgumentCaptor<CommunityMember> captor = ArgumentCaptor.forClass(CommunityMember.class);
        verify(communityMemberRepository).save(captor.capture());
        assertThat(result).isSameAs(captor.getValue());
        assertThat(result.getNickname()).isEqualTo("애플");
    }
}
