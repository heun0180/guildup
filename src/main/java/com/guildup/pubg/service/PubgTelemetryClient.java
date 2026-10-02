package com.guildup.pubg.service;

import tools.jackson.databind.JsonNode;
import com.guildup.pubg.exception.PubgApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import com.guildup.pubg.exception.PubgApiErrorCode;
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
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw new PubgApiException(status == 429 ? PubgApiErrorCode.PUBG_RATE_LIMITED
                    : status >= 500 ? PubgApiErrorCode.PUBG_UNAVAILABLE : PubgApiErrorCode.PUBG_ERROR,
                    "PUBG Telemetry를 불러오지 못했습니다.", exception, status, false);
        } catch (ResourceAccessException exception) {
            throw new PubgApiException(PubgApiErrorCode.PUBG_TIMEOUT,
                    "PUBG Telemetry 응답 시간이 초과되었습니다.", exception, null, false);
        } catch (RestClientException exception) {
            throw new PubgApiException("PUBG Telemetry를 불러오지 못했습니다.", exception);
        }
    }
}
