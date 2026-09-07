package com.guildup.community.service;

import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameActivityRule;
import com.guildup.community.dto.CommunityGameActivityRuleResponse;
import com.guildup.community.repository.CommunityGameActivityRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 커뮤니티 게임별 활동 규칙을 조회하고 갱신한다. */
@Service
public class CommunityGameActivityRuleService {

    private final CommunityAccessService accessService;
    private final CommunityGameRepository communityGameRepository;
    private final CommunityGameActivityRuleRepository ruleRepository;

    public CommunityGameActivityRuleService(
            CommunityAccessService accessService,
            CommunityGameRepository communityGameRepository,
            CommunityGameActivityRuleRepository ruleRepository
    ) {
        this.accessService = accessService;
        this.communityGameRepository = communityGameRepository;
        this.ruleRepository = ruleRepository;
    }

    @Transactional(readOnly = true)
    public CommunityGameActivityRuleResponse getRule(Long userId, Long communityId) {
        accessService.requireAccess(userId, communityId);
        CommunityGame communityGame = requireSupportedGame(communityId);
        CommunityGameActivityRule rule = ruleRepository.findByCommunityGameId(communityGame.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "저장된 클랜 활동 규칙이 없습니다."
                ));
        return CommunityGameActivityRuleResponse.from(rule);
    }

    @Transactional
    public CommunityGameActivityRuleResponse updateRule(
            Long userId,
            Long communityId,
            Integer activityPeriodDays,
            Integer minimumClanMembersInRoster
    ) {
        accessService.requireManagementAccess(userId, communityId);
        validateRequest(activityPeriodDays, minimumClanMembersInRoster);
        CommunityGame communityGame = requireSupportedGame(communityId);
        CommunityGameActivityRule rule = ruleRepository.findByCommunityGameId(communityGame.getId())
                .orElseGet(() -> CommunityGameActivityRule.defaultRule(communityGame));
        rule.configure(activityPeriodDays, minimumClanMembersInRoster);
        return CommunityGameActivityRuleResponse.from(ruleRepository.save(rule));
    }

    private CommunityGame requireSupportedGame(Long communityId) {
        return communityGameRepository.findByCommunityIdOrderByIdAsc(communityId).stream()
                .filter(game -> game.getGameType().supportsRosterActivityRule())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "이 커뮤니티에는 클랜 활동 규칙을 지원하는 배틀그라운드 게임이 없습니다."
                ));
    }

    private void validateRequest(Integer activityPeriodDays, Integer minimumClanMembersInRoster) {
        if (activityPeriodDays == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "활동 확인 기간을 입력해 주세요.");
        }
        if (minimumClanMembersInRoster == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "활동 인정 인원을 입력해 주세요.");
        }
        try {
            CommunityGameActivityRule.validate(activityPeriodDays, minimumClanMembersInRoster);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }
}
