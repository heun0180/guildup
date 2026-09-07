package com.guildup.community.repository;

import com.guildup.community.domain.CommunityGame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

/** 커뮤니티에 연결된 게임 종류를 저장하고 조회한다. */
public interface CommunityGameRepository extends JpaRepository<CommunityGame, Long> {

    List<CommunityGame> findByCommunityIdOrderByIdAsc(Long communityId);

    List<CommunityGame> findByCommunityIdInOrderByIdAsc(Collection<Long> communityIds);

    Optional<CommunityGame> findFirstByCommunityIdOrderByIdAsc(Long communityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from CommunityGame game where game.id = :id")
    Optional<CommunityGame> findByIdForUpdate(Long id);
}
