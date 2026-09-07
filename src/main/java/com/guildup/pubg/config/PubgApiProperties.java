package com.guildup.pubg.config;

import com.guildup.pubg.exception.PubgApiException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** PUBG API 서버 주소와 서버 전용 인증 키다. */
@ConfigurationProperties(prefix = "pubg")
public record PubgApiProperties(String apiKey, String baseUrl) {

    public String requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new PubgApiException("PUBG_API_KEY가 설정되지 않았습니다.");
        }
        return apiKey;
    }
}
