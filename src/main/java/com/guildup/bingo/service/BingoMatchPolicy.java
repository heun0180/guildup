package com.guildup.bingo.service;

import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgMatchCategory;
import org.springframework.stereotype.Component;

/** 빙고는 일반전과 경쟁전으로 확실히 분류된 경기만 인정한다. */
@Component
public class BingoMatchPolicy {
    public boolean isEligible(PubgMatch match) {
        PubgMatchCategory category = category(match);
        return category == PubgMatchCategory.NORMAL || category == PubgMatchCategory.RANKED;
    }

    public PubgMatchCategory category(PubgMatch match) {
        return PubgMatchCategory.from(match);
    }
}
