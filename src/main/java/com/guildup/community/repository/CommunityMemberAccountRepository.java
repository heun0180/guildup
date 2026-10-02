package com.guildup.community.repository;

import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.pubg.model.PubgPlatform;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 클랜원과 외부 서비스 계정의 연결을 저장하고 조회한다. */
public interface CommunityMemberAccountRepository extends JpaRepository<CommunityMemberAccount, Long> {

    /** 한 Community의 외부 계정을 멤버와 함께 읽어 동기화와 목록 변환의 N+1을 막는다. */
    @EntityGraph(attributePaths = "communityMember")
    @Query("select a from CommunityMemberAccount a where a.community.id = :communityId and a.provider = :provider and a.platform is null and a.provider <> com.guildup.account.domain.ExternalAccountProvider.PUBG")
    List<CommunityMemberAccount> findByCommunityIdAndProvider(
            Long communityId,
            ExternalAccountProvider provider
    );

    @EntityGraph(attributePaths = "communityMember")
    @Query("select a from CommunityMemberAccount a where a.community.id = :communityId and a.provider = :provider and a.externalUserId = :externalUserId and a.platform is null and a.provider <> com.guildup.account.domain.ExternalAccountProvider.PUBG")
    Optional<CommunityMemberAccount> findByCommunityIdAndProviderAndExternalUserId(
            Long communityId,
            ExternalAccountProvider provider,
            String externalUserId
    );

    @Query("select a from CommunityMemberAccount a where a.communityMember.id = :communityMemberId and a.provider = :provider and a.platform is null and a.provider <> com.guildup.account.domain.ExternalAccountProvider.PUBG")
    Optional<CommunityMemberAccount> findByCommunityMemberIdAndProvider(
            Long communityMemberId,
            ExternalAccountProvider provider
    );

    @EntityGraph(attributePaths = "communityMember")
    List<CommunityMemberAccount> findByCommunityIdAndProviderAndPlatform(
            Long communityId, ExternalAccountProvider provider, PubgPlatform platform);

    boolean existsByCommunityIdAndProviderAndPlatformIsNull(
            Long communityId, ExternalAccountProvider provider);

    Optional<CommunityMemberAccount> findByCommunityMemberIdAndProviderAndPlatform(
            Long communityMemberId, ExternalAccountProvider provider, PubgPlatform platform);

    @EntityGraph(attributePaths = "communityMember")
    Optional<CommunityMemberAccount> findByCommunityIdAndProviderAndPlatformAndExternalUserId(
            Long communityId, ExternalAccountProvider provider, PubgPlatform platform, String externalUserId);

    /** 목록 응답에만 사용한다. 콘텐츠별 조회는 반드시 선택한 플랫폼으로 제한한다. */
    @EntityGraph(attributePaths = "communityMember")
    @Query("select a from CommunityMemberAccount a where a.community.id = :communityId and a.provider = com.guildup.account.domain.ExternalAccountProvider.PUBG order by a.platform, a.id")
    List<CommunityMemberAccount> findPubgAccountsByCommunityId(Long communityId);
}
