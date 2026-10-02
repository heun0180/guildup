package com.guildup.pubg.service;

import com.guildup.pubg.model.PlayerMatchFacts;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgPlatform;

import java.util.Map;
import java.util.Set;

/** PUBG 수집 계층이 콘텐츠별 parser 구현과 결합되지 않게 하는 fact 추출 계약이다. */
public interface PubgMatchFactProvider {
    /** Returns only facts from successfully loaded Telemetry; fallback must never be persisted as loaded. */
    Map<String, PlayerMatchFacts> factsRequired(PubgPlatform platform, PubgMatch match, Set<String> communityAccounts);
}
