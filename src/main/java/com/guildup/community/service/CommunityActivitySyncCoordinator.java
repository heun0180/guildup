package com.guildup.community.service;

import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameActivitySync;
import com.guildup.community.domain.CommunityGameActivitySyncStatus;
import com.guildup.community.domain.GameCapability;
import com.guildup.community.dto.CommunityActivitySyncResponse;
import com.guildup.community.repository.CommunityGameActivitySyncRepository;
import com.guildup.community.repository.CommunityGameRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

@Service
public class CommunityActivitySyncCoordinator {

    private final CommunityGameAccessService gameAccess;
    private final CommunityGameRepository gameRepository;
    private final CommunityGameActivitySyncRepository syncRepository;
    private final CommunityActivitySyncPolicy syncPolicy;
    private final Clock clock;

    public CommunityActivitySyncCoordinator(
            CommunityGameAccessService gameAccess,
            CommunityGameRepository gameRepository,
            CommunityGameActivitySyncRepository syncRepository,
            CommunityActivitySyncPolicy syncPolicy,
            Clock clock
    ) {
        this.gameAccess = gameAccess;
        this.gameRepository = gameRepository;
        this.syncRepository = syncRepository;
        this.syncPolicy = syncPolicy;
        this.clock = clock;
    }

    @Transactional
    public Long begin(Long userId, Long communityId, Long communityGameId) {
        gameAccess.requireManageable(userId, communityId, communityGameId, GameCapability.ACTIVITY);
        CommunityGame game = gameRepository.findByIdForUpdate(communityGameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "배틀그라운드 게임 설정을 찾을 수 없습니다."
                ));
        CommunityGameActivitySync sync = syncRepository.findByCommunityGameId(game.getId())
                .orElseGet(() -> new CommunityGameActivitySync(game));
        CommunityActivitySyncResponse availability = syncPolicy.describe(sync, clock.instant());
        if (!availability.syncAvailable()) {
            if (sync.getSyncStatus() == CommunityGameActivitySyncStatus.SYNCING) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "이미 활동 정보를 조회 중입니다."
                );
            }
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "아직 활동 정보를 다시 조회할 수 없습니다."
            );
        }
        sync.start(clock.instant());
        syncRepository.save(sync);
        return game.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long communityGameId) {
        gameRepository.findByIdForUpdate(communityGameId).orElseThrow();
        syncRepository.findByCommunityGameId(communityGameId).ifPresent(sync -> {
            if (sync.getSyncStatus() == CommunityGameActivitySyncStatus.SYNCING) sync.fail();
        });
    }

}
