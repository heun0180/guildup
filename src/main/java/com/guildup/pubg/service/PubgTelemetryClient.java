package com.guildup.pubg.service;

import tools.jackson.databind.JsonNode;
import com.guildup.pubg.exception.PubgApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.beans.factory.annotation.Qualifier;

/** Match asset에 포함된 공식 Telemetry URL을 서버에서 한 번 내려받는다. */
@Component
public class PubgTelemetryClient {
    private final RestClient client;
    public PubgTelemetryClient(@Qualifier("pubgRestClient") RestClient client) { this.client = client; }

    public JsonNode get(String url) {
        if (url == null || !url.startsWith("https://telemetry-cdn.pubg.com/")) return null;
        try {
            return client.get().uri(url)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException exception) {
            throw new PubgApiException("PUBG Telemetry를 불러오지 못했습니다.", exception);
        }
    }
}
