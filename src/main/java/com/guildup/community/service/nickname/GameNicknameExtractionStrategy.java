package com.guildup.community.service.nickname;

import com.guildup.community.domain.GameNicknameRuleStrategyType;

import java.util.List;
import java.util.Optional;

/** 닉네임 샘플에서 후보를 만들고 같은 후보를 다른 닉네임에 적용한다. */
public interface GameNicknameExtractionStrategy {

    GameNicknameRuleStrategyType getType();

    List<NicknameRuleCandidate> infer(String discordNickname, String gameNickname);

    Optional<String> extract(String discordNickname, NicknameRuleCandidate candidate);

    int priority(NicknameRuleCandidate candidate);

    String describe(NicknameRuleCandidate candidate);
}
