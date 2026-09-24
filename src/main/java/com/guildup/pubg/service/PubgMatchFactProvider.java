package com.guildup.pubg.service;

import com.guildup.pubg.model.PlayerMatchFacts;
import com.guildup.pubg.model.PubgMatch;

import java.util.Map;
import java.util.Set;

/** PUBG 수집 계층이 콘텐츠별 parser 구현과 결합되지 않게 하는 fact 추출 계약이다. */
public interface PubgMatchFactProvider {
    Map<String, PlayerMatchFacts> facts(PubgMatch match, Set<String> communityAccounts);
}
