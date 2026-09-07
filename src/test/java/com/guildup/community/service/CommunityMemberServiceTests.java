package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityMemberServiceTests {

    private final CommunityRepository communityRepository = mock(CommunityRepository.class);
    private final CommunityMemberRepository communityMemberRepository = mock(CommunityMemberRepository.class);
    private final CommunityMemberAccountRepository accountRepository = mock(CommunityMemberAccountRepository.class);
    private final CommunityMemberService communityMemberService =
            new CommunityMemberService(communityRepository, communityMemberRepository, accountRepository);

    @Test
    void addsManualMemberWithoutExternalAccountData() {
        Community community = new Community("GuildUp 클랜");
        when(communityRepository.findById(1L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.save(org.mockito.ArgumentMatchers.any(CommunityMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommunityMember result = communityMemberService.addMember(1L, "애플");

        ArgumentCaptor<CommunityMember> captor = ArgumentCaptor.forClass(CommunityMember.class);
        verify(communityMemberRepository).save(captor.capture());
        assertThat(result).isSameAs(captor.getValue());
        assertThat(result.getNickname()).isEqualTo("애플");
    }
}
