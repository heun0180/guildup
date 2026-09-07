package com.guildup.community.config;

import com.guildup.community.domain.CommunityGameActivityRule;
import com.guildup.community.repository.CommunityGameActivityRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 기존 PUBG 커뮤니티 게임에 기본 활동 규칙을 한 번씩 안전하게 채운다. */
@Component
public class CommunityGameActivityRuleDataMigration implements ApplicationRunner {

    private final CommunityGameRepository communityGameRepository;
    private final CommunityGameActivityRuleRepository ruleRepository;

    public CommunityGameActivityRuleDataMigration(
            CommunityGameRepository communityGameRepository,
            CommunityGameActivityRuleRepository ruleRepository
    ) {
        this.communityGameRepository = communityGameRepository;
        this.ruleRepository = ruleRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        communityGameRepository.findAll().stream()
                .filter(game -> game.getGameType().supportsRosterActivityRule())
                .filter(game -> ruleRepository.findByCommunityGameId(game.getId()).isEmpty())
                .map(CommunityGameActivityRule::defaultRule)
                .forEach(ruleRepository::save);
    }
}
