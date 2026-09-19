package com.guildup.community.service;

import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameActivityRule;
import com.guildup.community.domain.GameCapability;
import com.guildup.community.dto.CommunityGameActivityRuleResponse;
import com.guildup.community.repository.CommunityGameActivityRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 커뮤니티 게임별 활동 규칙을 조회하고 갱신한다. */
@Service
public class CommunityGameActivityRuleService {

    private final CommunityGameAccessService gameAccess;
    private final CommunityGameActivityRuleRepository ruleRepository;

    @Autowired
    public CommunityGameActivityRuleService(
            CommunityGameAccessService gameAccess,
            CommunityGameActivityRuleRepository ruleRepository
    ) {
        this.gameAccess = gameAccess;
        this.ruleRepository = ruleRepository;
    }

    CommunityGameActivityRuleService(CommunityAccessService accessService,
                                            CommunityGameRepository games,
                                            CommunityGameActivityRuleRepository rules) {
        this(new CommunityGameAccessService(accessService, games), rules);
    }

    @Transactional(readOnly = true)
    public CommunityGameActivityRuleResponse getRule(Long userId, Long communityId, Long communityGameId) {
        CommunityGame communityGame = gameAccess.requireAccessible(userId, communityId, communityGameId, GameCapability.ACTIVITY);
        CommunityGameActivityRule rule = ruleRepository.findByCommunityGameId(communityGame.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "저장된 클랜 활동 규칙이 없습니다."
                ));
        return CommunityGameActivityRuleResponse.from(rule);
    }
    public CommunityGameActivityRuleResponse getRule(Long userId, Long communityId) {
        return getRule(userId, communityId,
                gameAccess.requireOnlyAccessible(userId, communityId, GameCapability.ACTIVITY).getId());
    }

    @Transactional
    public CommunityGameActivityRuleResponse updateRule(
            Long userId,
            Long communityId,
            Long communityGameId,
            Integer activityPeriodDays,
            Integer minimumClanMembersInRoster
    ) {
        CommunityGame communityGame = gameAccess.requireManageable(userId, communityId, communityGameId, GameCapability.ACTIVITY);
        validateRequest(activityPeriodDays, minimumClanMembersInRoster);
        CommunityGameActivityRule rule = ruleRepository.findByCommunityGameId(communityGame.getId())
                .orElseGet(() -> CommunityGameActivityRule.defaultRule(communityGame));
        rule.configure(activityPeriodDays, minimumClanMembersInRoster);
        return CommunityGameActivityRuleResponse.from(ruleRepository.save(rule));
    }
    public CommunityGameActivityRuleResponse updateRule(Long userId, Long communityId,
                                                        Integer activityPeriodDays, Integer minimumClanMembersInRoster) {
        return updateRule(userId, communityId,
                gameAccess.requireOnlyManageable(userId, communityId, GameCapability.ACTIVITY).getId(),
                activityPeriodDays, minimumClanMembersInRoster);
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
