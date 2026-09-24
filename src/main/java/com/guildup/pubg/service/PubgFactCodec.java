package com.guildup.pubg.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** DB 벤더별 JSON 타입에 의존하지 않는 작은 fact map codec이다. */
final class PubgFactCodec {
    private PubgFactCodec() {}

    static String decimals(Map<String, BigDecimal> values) {
        StringBuilder result = new StringBuilder();
        values.forEach((key, value) -> result.append(key(key)).append('=').append(value.toPlainString()).append('\n'));
        return result.toString();
    }

    static Map<String, BigDecimal> decimals(String value) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        lines(value).forEach((key, raw) -> result.put(key, new BigDecimal(raw)));
        return result;
    }

    static String integers(Map<String, Integer> values) {
        StringBuilder result = new StringBuilder();
        values.forEach((key, value) -> result.append(key(key)).append('=').append(value).append('\n'));
        return result.toString();
    }

    static Map<String, Integer> integers(String value) {
        Map<String, Integer> result = new LinkedHashMap<>();
        lines(value).forEach((key, raw) -> result.put(key, Integer.parseInt(raw)));
        return result;
    }

    private static Map<String, String> lines(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) return result;
        for (String line : value.split("\\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) result.put(unkey(line.substring(0, separator)), line.substring(separator + 1));
        }
        return result;
    }

    private static String key(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unkey(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
