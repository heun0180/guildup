package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameNicknameRule;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.dto.CommunityGameNicknameSyncResponse;
import com.guildup.community.repository.CommunityGameNicknameRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.service.nickname.GameNicknameRuleInferenceService;
import com.guildup.community.service.nickname.NicknameRuleCandidate;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.service.PubgPlayerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 저장된 Discord 닉네임 규칙으로 PUBG 계정을 확인하고 클랜원 계정에 반영한다. */
@Service
public class CommunityGameNicknameSyncService {

    private final CommunityAccessService accessService;
    private final CommunityGameRepository gameRepository;
    private final CommunityGameNicknameRuleRepository ruleRepository;
    private final CommunityMemberRepository memberRepository;
    private final CommunityMemberAccountRepository accountRepository;
    private final GameNicknameRuleInferenceService nicknameInference;
    private final PubgPlayerService playerService;

    public CommunityGameNicknameSyncService(
            CommunityAccessService accessService,
            CommunityGameRepository gameRepository,
            CommunityGameNicknameRuleRepository ruleRepository,
            CommunityMemberRepository memberRepository,
            CommunityMemberAccountRepository accountRepository,
            GameNicknameRuleInferenceService nicknameInference,
            PubgPlayerService playerService
    ) {
        this.accessService = accessService;
        this.gameRepository = gameRepository;
        this.ruleRepository = ruleRepository;
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.nicknameInference = nicknameInference;
        this.playerService = playerService;
    }

    @Transactional
    public CommunityGameNicknameSyncResponse synchronize(Long userId, Long communityId) {
        accessService.requireManagementAccess(userId, communityId);
        CommunityGame game = gameRepository.findFirstByCommunityIdOrderByIdAsc(communityId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "커뮤니티에서 사용할 게임이 설정되지 않았습니다."
                ));
        CommunityGameNicknameRule rule = ruleRepository
                .findByCommunityIdAndGameType(communityId, game.getGameType())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "인게임 닉네임 규칙을 먼저 설정해 주세요."
                ));
        NicknameRuleCandidate candidate = candidate(rule);
        List<CommunityMember> members = memberRepository.findByCommunityIdAndStatusOrderByIdAsc(
                communityId, CommunityMemberStatus.ACTIVE
        );
        Map<Long, CommunityMemberAccount> existingAccounts = accountRepository
                .findByCommunityIdAndProvider(communityId, ExternalAccountProvider.PUBG).stream()
                .filter(account -> account.getCommunityMember().getStatus() == CommunityMemberStatus.ACTIVE)
                .collect(Collectors.toMap(
                        account -> account.getCommunityMember().getId(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));

        Map<Long, String> extractedNames = members.stream()
                .map(member -> Map.entry(
                        member.getId(),
                        nicknameInference.extract(candidate, member.getNickname()).orElse("")
                ))
                .filter(entry -> !entry.getValue().isBlank())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        List<String> namesToLookup = extractedNames.values().stream().distinct().toList();
        Map<String, PubgPlayer> playersByName = playerService
                .findByNamesFresh(game.getGameType().getPubgShard(), namesToLookup).stream()
                .filter(player -> player.name() != null && player.accountId() != null)
                .collect(Collectors.toMap(
                        player -> normalize(player.name()),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Set<String> claimedAccountIds = new LinkedHashSet<>();
        List<CommunityMemberAccount> synchronizedAccounts = new ArrayList<>();
        int createdAccounts = 0;
        int updatedAccounts = 0;
        for (CommunityMember member : members) {
            String extractedName = extractedNames.get(member.getId());
            PubgPlayer player = extractedName == null ? null : playersByName.get(normalize(extractedName));
            if (player == null || !claimedAccountIds.add(player.accountId())) continue;

            CommunityMemberAccount existing = existingAccounts.get(member.getId());
            if (existing == null) {
                createdAccounts++;
            } else if (!existing.getExternalUserId().equals(player.accountId())
                    || !Objects.equals(existing.getExternalUsername(), player.name())) {
                updatedAccounts++;
            }
            synchronizedAccounts.add(new CommunityMemberAccount(
                    member, ExternalAccountProvider.PUBG, player.accountId(), player.name()
            ));
        }

        if (!existingAccounts.isEmpty()) {
            accountRepository.deleteAllInBatch(existingAccounts.values());
        }
        if (!synchronizedAccounts.isEmpty()) accountRepository.saveAll(synchronizedAccounts);

        int synchronizedMembers = synchronizedAccounts.size();
        int failedMembers = members.size() - synchronizedMembers;

        return new CommunityGameNicknameSyncResponse(
                members.size(),
                synchronizedMembers,
                createdAccounts,
                updatedAccounts,
                failedMembers
        );
    }

    private NicknameRuleCandidate candidate(CommunityGameNicknameRule rule) {
        return new NicknameRuleCandidate(
                rule.getStrategyType(), rule.getDelimiterType(), rule.getSegmentIndex(),
                rule.isFromEnd(), rule.getExpectedSegmentCount()
        );
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
