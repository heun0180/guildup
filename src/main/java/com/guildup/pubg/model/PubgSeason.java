package com.guildup.pubg.model;

/** 팀 편성에 필요한 PUBG 시즌 식별 정보다. */
public record PubgSeason(String id, boolean current, boolean offseason) {}
