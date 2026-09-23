package com.guildup.pubg.model;

import java.util.Locale;

/** PUBG Match 응답의 경기 종류를 콘텐츠 정책과 분리해 공통 분류한다. */
public enum PubgMatchCategory {
    NORMAL,
    RANKED,
    CASUAL,
    CUSTOM,
    ARCADE,
    OTHER;

    public static PubgMatchCategory from(PubgMatch match) {
        if (match == null) return OTHER;
        if (Boolean.TRUE.equals(match.customMatch())) return CUSTOM;
        String matchType = match.matchType();
        if (matchType == null || matchType.isBlank()) return OTHER;
        return switch (matchType.trim().toLowerCase(Locale.ROOT)) {
            case "official" -> Boolean.FALSE.equals(match.customMatch()) ? NORMAL : OTHER;
            case "competitive" -> Boolean.FALSE.equals(match.customMatch()) ? RANKED : OTHER;
            case "airoyale" -> CASUAL;
            case "custom" -> CUSTOM;
            case "arcade", "event", "seasonal", "training" -> ARCADE;
            default -> OTHER;
        };
    }
}
