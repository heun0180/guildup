package com.guildup.community.repository;

import com.guildup.community.domain.CommunityGameActivitySync;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CommunityGameActivitySyncRepository
        extends JpaRepository<CommunityGameActivitySync, Long> {

    Optional<CommunityGameActivitySync> findByCommunityGameId(Long communityGameId);
}
