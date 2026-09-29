package com.guildup.pubg.support;

/** PUBG가 AI 캐릭터에 발급하는 accountId namespace를 한 곳에서 판별한다. */
public final class PubgAiBotSupport {
    private static final String AI_ACCOUNT_PREFIX = "ai.";

    private PubgAiBotSupport() {}

    public static boolean isAiBot(String accountId) {
        return accountId != null && accountId.startsWith(AI_ACCOUNT_PREFIX);
    }
}
