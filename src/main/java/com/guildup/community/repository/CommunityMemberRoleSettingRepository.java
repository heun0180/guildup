package com.guildup.community.repository;

import com.guildup.community.domain.CommunityMemberRoleSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 커뮤니티별 클랜원 판별 Discord 역할 설정을 저장한다. */
public interface CommunityMemberRoleSettingRepository
        extends JpaRepository<CommunityMemberRoleSetting, Long> {

    List<CommunityMemberRoleSetting> findByCommunityIdOrderByIdAsc(Long communityId);

    @Modifying(flushAutomatically = true)
    @Query("delete from CommunityMemberRoleSetting setting where setting.community.id = :communityId")
    int deleteByCommunityId(@Param("communityId") Long communityId);
}
