package com.guildup.community.service.nickname;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameNicknameRuleInferenceServiceTests {

    private GameNicknameRuleInferenceService inferenceService;

    @BeforeEach
    void setUp() {
        inferenceService = new GameNicknameRuleInferenceService(List.of(
                new DelimitedSegmentNicknameStrategy(),
                new FullNicknameExtractionStrategy()
        ));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "애플/93/sa-gwa;sa-gwa",
            "sa-gwa 애플 93;sa-gwa",
            "애플 93 sa-gwa;sa-gwa",
            "애플(93) sa-gwa;sa-gwa",
            "애플 | sa-gwa | 93;sa-gwa",
            "애플/93/ABC_123;ABC_123",
            "애플 | Player.Name | 93;Player.Name"
    }, delimiter = ';')
    void infersCommonNicknameFormatsWithoutSplittingGameNicknameCharacters(
            String discordNickname,
            String gameNickname
    ) {
        var candidates = inferenceService.infer(discordNickname, gameNickname);
        var selected = inferenceService.selectBest(candidates, List.of(discordNickname));

        assertThat(inferenceService.extract(selected, discordNickname)).contains(gameNickname);
    }

    @Test
    void rejectsMissingAndRepeatedSampleText() {
        assertThatThrownBy(() -> inferenceService.infer("애플/93/sa-gwa", "  "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("입력해 주세요");
        assertThatThrownBy(() -> inferenceService.infer("애플/93/sa-gwa", "not-found"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("찾을 수 없습니다");
        assertThatThrownBy(() -> inferenceService.infer("sa-gwa/93/sa-gwa", "sa-gwa"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("여러 번");
    }

    @Test
    void rejectsAContainedNicknameWhenNoGeneralRuleCanBeCreated() {
        assertThatThrownBy(() -> inferenceService.infer("prefix-sa-gwa-suffix", "sa-gwa"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("공통으로 사용할 규칙을 만들 수 없습니다");
    }

    @Test
    void keepsMembersThatDoNotMatchAsExtractionFailures() {
        var candidates = inferenceService.infer("애플(93) sa-gwa", "sa-gwa");
        var selected = inferenceService.selectBest(candidates, List.of(
                "애플(93) sa-gwa",
                "절미(95) jul-mi",
                "감자"
        ));

        assertThat(inferenceService.extract(selected, "애플(93) sa-gwa")).contains("sa-gwa");
        assertThat(inferenceService.extract(selected, "절미(95) jul-mi")).contains("jul-mi");
        assertThat(inferenceService.extract(selected, "감자")).isEmpty();
    }

    @Test
    void trimsInputAndMatchesSampleIgnoringCase() {
        var candidates = inferenceService.infer("애플/93/Player.Name", "  player.name  ");
        var selected = inferenceService.selectBest(candidates, List.of("애플/93/Player.Name"));

        assertThat(inferenceService.extract(selected, "애플/93/Player.Name")).contains("Player.Name");
    }
}
