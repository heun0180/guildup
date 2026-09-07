package com.guildup.community.service.nickname;

import com.guildup.community.domain.GameNicknameDelimiterType;
import com.guildup.community.domain.GameNicknameRuleStrategyType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 슬래시, 파이프, 공백으로 나뉜 항목 중 하나를 사용하는 범용 전략이다. */
@Component
public class DelimitedSegmentNicknameStrategy implements GameNicknameExtractionStrategy {

    @Override
    public GameNicknameRuleStrategyType getType() {
        return GameNicknameRuleStrategyType.DELIMITED_SEGMENT;
    }

    @Override
    public List<NicknameRuleCandidate> infer(String discordNickname, String gameNickname) {
        List<NicknameRuleCandidate> candidates = new ArrayList<>();
        for (GameNicknameDelimiterType delimiter : GameNicknameDelimiterType.values()) {
            List<String> segments = split(discordNickname, delimiter);
            if (segments.size() < 2) continue;

            for (int index = 0; index < segments.size(); index++) {
                if (!segments.get(index).equalsIgnoreCase(gameNickname)) continue;
                boolean fromEnd = index == segments.size() - 1;
                int storedIndex = fromEnd ? 0 : index;
                candidates.add(new NicknameRuleCandidate(
                        getType(), delimiter, storedIndex, fromEnd, segments.size()
                ));
            }
        }
        return candidates;
    }

    @Override
    public Optional<String> extract(String discordNickname, NicknameRuleCandidate candidate) {
        if (discordNickname == null || candidate.delimiterType() == null
                || candidate.segmentIndex() == null || candidate.expectedSegmentCount() == null) {
            return Optional.empty();
        }
        List<String> segments = split(discordNickname.trim(), candidate.delimiterType());
        if (segments.size() != candidate.expectedSegmentCount()) return Optional.empty();

        int index = candidate.fromEnd()
                ? segments.size() - 1 - candidate.segmentIndex()
                : candidate.segmentIndex();
        if (index < 0 || index >= segments.size()) return Optional.empty();
        String extracted = segments.get(index).trim();
        return extracted.isEmpty() ? Optional.empty() : Optional.of(extracted);
    }

    @Override
    public int priority(NicknameRuleCandidate candidate) {
        return candidate.delimiterType() == GameNicknameDelimiterType.WHITESPACE ? 20 : 30;
    }

    @Override
    public String describe(NicknameRuleCandidate candidate) {
        String boundary = candidate.fromEnd() && candidate.segmentIndex() == 0
                ? "마지막 부분"
                : !candidate.fromEnd() && candidate.segmentIndex() == 0
                ? "첫 번째 부분"
                : "가운데 부분";
        return switch (candidate.delimiterType()) {
            case SLASH -> "닉네임에서 /로 나뉜 " + boundary + "을 사용합니다.";
            case PIPE -> "닉네임에서 |로 나뉜 " + boundary + "을 사용합니다.";
            case WHITESPACE -> "닉네임에서 공백으로 나뉜 " + boundary + "을 사용합니다.";
        };
    }

    private List<String> split(String value, GameNicknameDelimiterType delimiter) {
        if (value == null) return List.of();
        String normalized = value.trim();
        if (normalized.isEmpty()) return List.of();
        String[] values = switch (delimiter) {
            case SLASH -> normalized.split("/", -1);
            case PIPE -> normalized.split("\\|", -1);
            case WHITESPACE -> normalized.split("\\s+");
        };
        return Arrays.stream(values).map(String::trim).toList();
    }
}
