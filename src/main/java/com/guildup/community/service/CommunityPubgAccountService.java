package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.GameCapability;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.pubg.service.PubgPlayerService;
import com.guildup.pubg.support.PubgGameSupport;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Discord 닉네임 분석 없이 본인의 게임 계정을 등록하는 경로다. 기존 플랫폼별 계정 구조를 사용한다. */
@Service
public class CommunityPubgAccountService {
    private final CommunityGameAccessService gameAccess;
    private final CurrentCommunityMemberService currentMembers;
    private final CommunityMemberAccountRepository accounts;
    private final PubgPlayerService players;

    public CommunityPubgAccountService(CommunityGameAccessService gameAccess, CurrentCommunityMemberService currentMembers,
                                       CommunityMemberAccountRepository accounts, PubgPlayerService players) {
        this.gameAccess = gameAccess;
        this.currentMembers = currentMembers;
        this.accounts = accounts;
        this.players = players;
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Long userId, Long communityId, Long gameId) {
        var game = gameAccess.requireAccessible(userId, communityId, gameId, GameCapability.NICKNAME_SYNC);
        var member = currentMembers.require(userId, communityId);
        var account = accounts.findByCommunityMemberIdAndProviderAndPlatform(member.getId(), ExternalAccountProvider.PUBG,
                PubgGameSupport.requirePlatform(game));
        return account.map(value -> new AccountResponse(value.getExternalUsername(), value.getExternalUserId()))
                .orElseGet(() -> new AccountResponse(null, null));
    }

    @Transactional
    public AccountResponse save(Long userId, Long communityId, Long gameId, String nickname) {
        var game = gameAccess.requireAccessible(userId, communityId, gameId, GameCapability.NICKNAME_SYNC);
        var member = currentMembers.requireForUpdate(userId, communityId);
        if (nickname == null || nickname.isBlank() || nickname.trim().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "게임 닉네임을 입력해 주세요.");
        }
        var player = players.findByNamesFresh(PubgGameSupport.requireShard(game.getGameType()), java.util.List.of(nickname.trim())).stream()
                .filter(value -> value.name() != null && value.name().equalsIgnoreCase(nickname.trim())
                        && value.accountId() != null && !value.accountId().isBlank())
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "해당 플랫폼에서 게임 계정을 찾을 수 없습니다."));
        var platform = PubgGameSupport.requirePlatform(game);
        var other = accounts.findByCommunityIdAndProviderAndPlatformAndExternalUserId(
                communityId, ExternalAccountProvider.PUBG, platform, player.accountId());
        if (other.isPresent() && !other.get().getCommunityMember().getId().equals(member.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 다른 클랜원에게 연결된 게임 계정입니다.");
        }
        accounts.findByCommunityMemberIdAndProviderAndPlatform(member.getId(), ExternalAccountProvider.PUBG, platform)
                .ifPresent(account -> accounts.deleteAllInBatch(java.util.List.of(account)));
        try {
            accounts.saveAndFlush(new CommunityMemberAccount(member, platform, player.accountId(), player.name()));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 다른 클랜원에게 연결된 게임 계정입니다.");
        }
        return new AccountResponse(player.name(), player.accountId());
    }

    public record AccountResponse(String nickname, String accountId) { }
}
