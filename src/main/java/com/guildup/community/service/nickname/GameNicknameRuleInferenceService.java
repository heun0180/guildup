package com.guildup.community.service.nickname;

import com.guildup.community.domain.GameNicknameRuleStrategyType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 사용자 샘플로 후보를 만들고 Discord 멤버 적용 성공 수가 가장 높은 규칙을 선택한다. */
@Service
public class GameNicknameRuleInferenceService {

    private final List<GameNicknameExtractionStrategy> strategies;
    private final Map<GameNicknameRuleStrategyType, GameNicknameExtractionStrategy> strategiesByType;

    public GameNicknameRuleInferenceService(List<GameNicknameExtractionStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
        this.strategiesByType = strategies.stream().collect(Collectors.toUnmodifiableMap(
                GameNicknameExtractionStrategy::getType,
                Function.identity()
        ));
    }

    public List<NicknameRuleCandidate> infer(String discordNickname, String enteredGameNickname) {
        String gameNickname = requireGameNickname(enteredGameNickname);
        String source = discordNickname == null ? "" : discordNickname.trim();
        int occurrences = countOccurrencesIgnoringCase(source, gameNickname);
        if (occurrences == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "입력한 인게임 닉네임을 현재 Discord 닉네임에서 찾을 수 없습니다.");
        }
        if (occurrences > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "입력한 인게임 닉네임이 Discord 닉네임에 여러 번 나타납니다. 더 구분되는 값을 입력해 주세요.");
        }

        LinkedHashSet<NicknameRuleCandidate> candidates = strategies.stream()
                .flatMap(strategy -> strategy.infer(source, gameNickname).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "현재 닉네임 형식에서 공통으로 사용할 규칙을 만들 수 없습니다.");
        }
        return List.copyOf(candidates);
    }

    public NicknameRuleCandidate selectBest(List<NicknameRuleCandidate> candidates, List<String> discordNicknames) {
        return candidates.stream()
                .max(Comparator
                        .comparingInt((NicknameRuleCandidate candidate) -> successCount(candidate, discordNicknames))
                        .thenComparingInt(this::priority))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "사용할 수 있는 닉네임 추출 규칙이 없습니다."));
    }

    public Optional<String> extract(NicknameRuleCandidate candidate, String discordNickname) {
        return strategy(candidate).extract(discordNickname, candidate);
    }

    public String describe(NicknameRuleCandidate candidate) {
        return strategy(candidate).describe(candidate);
    }

    public String normalizeGameNickname(String enteredGameNickname) {
        return requireGameNickname(enteredGameNickname);
    }

    private int successCount(NicknameRuleCandidate candidate, List<String> discordNicknames) {
        return (int) discordNicknames.stream().filter(name -> extract(candidate, name).isPresent()).count();
    }

    private int priority(NicknameRuleCandidate candidate) {
        return strategy(candidate).priority(candidate);
    }

    private GameNicknameExtractionStrategy strategy(NicknameRuleCandidate candidate) {
        GameNicknameExtractionStrategy strategy = strategiesByType.get(candidate.strategyType());
        if (strategy == null) throw new IllegalStateException("Unsupported nickname rule: " + candidate.strategyType());
        return strategy;
    }

    private String requireGameNickname(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "인게임 닉네임을 입력해 주세요.");
        }
        return value.trim();
    }

    private int countOccurrencesIgnoringCase(String source, String target) {
        String normalizedSource = source.toLowerCase(Locale.ROOT);
        String normalizedTarget = target.toLowerCase(Locale.ROOT);
        int count = 0;
        for (int index = normalizedSource.indexOf(normalizedTarget);
             index >= 0;
             index = normalizedSource.indexOf(normalizedTarget, index + 1)) {
            count++;
        }
        return count;
    }
}
