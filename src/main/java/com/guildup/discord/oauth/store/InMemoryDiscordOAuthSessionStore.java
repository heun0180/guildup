package com.guildup.discord.oauth.store;

import com.guildup.discord.oauth.dto.DiscordOAuthResultResponse;
import com.guildup.discord.oauth.dto.DiscordManageableGuildResponse;
import com.guildup.discord.oauth.exception.DiscordOAuthResultNotFoundException;
import com.guildup.discord.oauth.exception.InvalidDiscordGuildSelectionException;
import com.guildup.discord.oauth.exception.InvalidDiscordOAuthStateException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth state와 인증 결과를 애플리케이션 메모리에 보관하는 구현체다.
 * 서버가 재시작되면 데이터가 사라지므로 단일 인스턴스 또는 개발 환경에 적합하다.
 */
@Component
public class InMemoryDiscordOAuthSessionStore implements DiscordOAuthSessionStore {

    // Discord 인증을 끝내고 콜백으로 돌아올 수 있는 최대 시간이다.
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    // 콜백 완료 후 화면이 사용자와 서버 목록을 조회할 수 있는 최대 시간이다.
    private static final Duration RESULT_TTL = Duration.ofMinutes(5);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, PendingState> states = new ConcurrentHashMap<>();
    private final Map<String, PendingResult> results = new ConcurrentHashMap<>();

    @Override
    public String createState(Long communityId) {
        removeExpiredEntries();
        String state = randomId();
        // state와 communityId를 함께 저장해 콜백에서 원래 연결 대상을 복원한다.
        states.put(state, new PendingState(communityId, Instant.now().plus(STATE_TTL)));
        return state;
    }

    @Override
    public Long consumeState(String state) {
        // 조회와 동시에 제거하여 같은 state를 이용한 콜백 재사용을 막는다.
        PendingState pendingState = state == null ? null : states.remove(state);
        if (pendingState == null || pendingState.expiresAt().isBefore(Instant.now())) {
            throw new InvalidDiscordOAuthStateException();
        }
        return pendingState.communityId();
    }

    @Override
    public String saveResult(Long communityId, DiscordOAuthResultResponse result) {
        removeExpiredEntries();
        String resultId = randomId();
        results.put(resultId, new PendingResult(communityId, result, Instant.now().plus(RESULT_TTL)));
        return resultId;
    }

    @Override
    public DiscordOAuthResultResponse getResult(Long communityId, String resultId) {
        PendingResult pendingResult = resultId == null ? null : results.get(resultId);
        // 결과 ID뿐 아니라 communityId도 비교해 다른 커뮤니티 결과의 조회를 막는다.
        if (pendingResult == null
                || !pendingResult.communityId().equals(communityId)
                || pendingResult.expiresAt().isBefore(Instant.now())) {
            throw new DiscordOAuthResultNotFoundException();
        }
        return pendingResult.result();
    }

    @Override
    public DiscordManageableGuildResponse consumeSelectedGuild(
            Long communityId,
            String resultId,
            String guildId
    ) {
        PendingResult pendingResult = resultId == null ? null : results.get(resultId);
        if (pendingResult == null
                || !pendingResult.communityId().equals(communityId)
                || pendingResult.expiresAt().isBefore(Instant.now())) {
            throw new DiscordOAuthResultNotFoundException();
        }

        // 브라우저가 보낸 guildId가 Discord에서 조회한 관리 가능 서버 목록 안에 있어야 한다.
        DiscordManageableGuildResponse selectedGuild = pendingResult.result().guilds().stream()
                .filter(guild -> guild.id().equals(guildId))
                .findFirst()
                .orElseThrow(InvalidDiscordGuildSelectionException::new);

        // 서버 선택이 끝난 OAuth 결과는 한 번만 사용할 수 있도록 원자적으로 제거한다.
        if (!results.remove(resultId, pendingResult)) {
            throw new DiscordOAuthResultNotFoundException();
        }
        return selectedGuild;
    }

    /** state와 결과 ID에 사용할 예측하기 어려운 256비트 난수를 생성한다. */
    private String randomId() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 새 데이터를 만들 때 만료된 state와 결과를 함께 정리한다. */
    private void removeExpiredEntries() {
        Instant now = Instant.now();
        states.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        results.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record PendingState(Long communityId, Instant expiresAt) {
    }

    private record PendingResult(
            Long communityId,
            DiscordOAuthResultResponse result,
            Instant expiresAt
    ) {
    }
}
