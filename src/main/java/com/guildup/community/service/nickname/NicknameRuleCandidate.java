package com.guildup.community.service.nickname;

import com.guildup.community.domain.GameNicknameDelimiterType;
import com.guildup.community.domain.GameNicknameRuleStrategyType;

/** 미리보기 검증과 DB 저장에 공통으로 사용하는 추출 규칙 값이다. */
public record NicknameRuleCandidate(
        GameNicknameRuleStrategyType strategyType,
        GameNicknameDelimiterType delimiterType,
        Integer segmentIndex,
        boolean fromEnd,
        Integer expectedSegmentCount
) {
}
