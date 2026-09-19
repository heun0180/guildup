package com.guildup.community.service;

import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.GameCapability;
import com.guildup.community.repository.CommunityGameRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CommunityGameAccessService {
    private final CommunityAccessService communities;
    private final CommunityGameRepository games;

    public CommunityGameAccessService(CommunityAccessService communities, CommunityGameRepository games) {
        this.communities = communities;
        this.games = games;
    }

    public CommunityGame requireAccessible(Long userId, Long communityId, Long communityGameId,
                                           GameCapability capability) {
        communities.requireCommunityMember(userId, communityId);
        return requireOwned(communityId, communityGameId, capability);
    }

    public CommunityGame requireManageable(Long userId, Long communityId, Long communityGameId,
                                           GameCapability capability) {
        communities.requireCommunityAdmin(userId, communityId);
        return requireOwned(communityId, communityGameId, capability);
    }

    public CommunityGame requireOwned(Long communityId, Long communityGameId, GameCapability capability) {
        CommunityGame game = games.findById(communityGameId)
                .filter(candidate -> candidate.getCommunity().getId().equals(communityId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "커뮤니티에 속한 게임을 찾을 수 없습니다."));
        if (!game.supports(capability)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "선택한 게임은 " + capability + " 기능을 지원하지 않습니다.");
        }
        return game;
    }

    /** Transitional Java-call compatibility; succeeds only when capability identifies exactly one game. */
    public CommunityGame requireOnlyManageable(Long userId, Long communityId, GameCapability capability) {
        communities.requireCommunityAdmin(userId, communityId);
        return requireOnly(communityId, capability);
    }

    public CommunityGame requireOnlyAccessible(Long userId, Long communityId, GameCapability capability) {
        communities.requireCommunityMember(userId, communityId);
        return requireOnly(communityId, capability);
    }

    private CommunityGame requireOnly(Long communityId, GameCapability capability) {
        List<CommunityGame> matches = games.findByCommunityIdOrderByIdAsc(communityId).stream()
                .filter(game -> game.supports(capability)).toList();
        if (matches.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "communityGameId를 명시해야 합니다.");
        }
        return matches.getFirst();
    }
}
