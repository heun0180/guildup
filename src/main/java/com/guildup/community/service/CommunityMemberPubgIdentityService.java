package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameNicknameRule;
import com.guildup.community.repository.CommunityGameNicknameRuleRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.service.nickname.GameNicknameRuleInferenceService;
import com.guildup.community.service.nickname.NicknameRuleCandidate;
import com.guildup.pubg.support.PubgGameSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 저장된 PUBG 계정을 우선 사용하고, 없으면 커뮤니티의 Discord 닉네임 규칙으로 조회 후보를 만든다. */
@Service
@Transactional(readOnly = true)
public class CommunityMemberPubgIdentityService {
    private final CommunityMemberAccountRepository accounts;
    private final CommunityGameNicknameRuleRepository nicknameRules;
    private final GameNicknameRuleInferenceService nicknameInference;

    public CommunityMemberPubgIdentityService(CommunityMemberAccountRepository accounts,
                                              CommunityGameNicknameRuleRepository nicknameRules,
                                              GameNicknameRuleInferenceService nicknameInference) {
        this.accounts = accounts;
        this.nicknameRules = nicknameRules;
        this.nicknameInference = nicknameInference;
    }

    public Optional<PubgIdentity> find(CommunityMember member, CommunityGame game) {
            String shard = PubgGameSupport.requireShard(game.getGameType());
            Optional<CommunityMemberAccount> stored = accounts.findByCommunityMemberIdAndProvider(
                    member.getId(), ExternalAccountProvider.PUBG).filter(this::usable);
            if (stored.isPresent()) {
                CommunityMemberAccount account = stored.get();
                return Optional.of(new PubgIdentity(shard, account.getExternalUsername(),
                        account.getExternalUserId()));
            }
            return nicknameRules.findByCommunityIdAndGameType(member.getCommunity().getId(), game.getGameType())
                    .flatMap(rule -> nicknameInference.extract(candidate(rule), member.getNickname()))
                    .filter(nickname -> !nickname.isBlank())
                    .map(nickname -> new PubgIdentity(shard, nickname, null));
    }

    private NicknameRuleCandidate candidate(CommunityGameNicknameRule rule) {
        return new NicknameRuleCandidate(rule.getStrategyType(), rule.getDelimiterType(), rule.getSegmentIndex(),
                rule.isFromEnd(), rule.getExpectedSegmentCount());
    }

    private boolean usable(CommunityMemberAccount account) {
        return account.getExternalUserId() != null && !account.getExternalUserId().isBlank()
                && account.getExternalUsername() != null && !account.getExternalUsername().isBlank();
    }

    public record PubgIdentity(String shard, String nickname, String accountId) {
        public boolean requiresLookup() {
            return accountId == null || accountId.isBlank();
        }
    }
}
