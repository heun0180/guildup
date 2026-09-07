package com.guildup.community.service.nickname;

import com.guildup.community.domain.GameNicknameRuleStrategyType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Discord 닉네임 전체가 인게임 닉네임인 경우를 처리한다. */
@Component
public class FullNicknameExtractionStrategy implements GameNicknameExtractionStrategy {

    @Override
    public GameNicknameRuleStrategyType getType() {
        return GameNicknameRuleStrategyType.FULL_NICKNAME;
    }

    @Override
    public List<NicknameRuleCandidate> infer(String discordNickname, String gameNickname) {
        if (discordNickname.trim().equalsIgnoreCase(gameNickname)) {
            return List.of(new NicknameRuleCandidate(getType(), null, null, false, null));
        }
        return List.of();
    }

    @Override
    public Optional<String> extract(String discordNickname, NicknameRuleCandidate candidate) {
        if (discordNickname == null || discordNickname.isBlank()) return Optional.empty();
        return Optional.of(discordNickname.trim());
    }

    @Override
    public int priority(NicknameRuleCandidate candidate) {
        return 40;
    }

    @Override
    public String describe(NicknameRuleCandidate candidate) {
        return "Discord 닉네임 전체를 인게임 닉네임으로 사용합니다.";
    }
}
