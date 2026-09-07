package com.guildup.community.repository;

import com.guildup.community.domain.CommunityGame;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 커뮤니티에 연결된 게임 종류를 저장하고 조회한다. */
public interface CommunityGameRepository extends JpaRepository<CommunityGame, Long> {

    List<CommunityGame> findByCommunityIdOrderByIdAsc(Long communityId);
}
