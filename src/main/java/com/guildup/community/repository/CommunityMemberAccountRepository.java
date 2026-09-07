package com.guildup.community.repository;

import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.account.domain.ExternalAccountProvider;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 클랜원과 외부 서비스 계정의 연결을 저장하고 조회한다. */
public interface CommunityMemberAccountRepository extends JpaRepository<CommunityMemberAccount, Long> {

    /** 한 Community의 외부 계정을 멤버와 함께 읽어 동기화와 목록 변환의 N+1을 막는다. */
    @EntityGraph(attributePaths = "communityMember")
    List<CommunityMemberAccount> findByCommunityIdAndProvider(
            Long communityId,
            ExternalAccountProvider provider
    );
}
