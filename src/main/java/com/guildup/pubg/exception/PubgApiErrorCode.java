package com.guildup.pubg.exception;

/** 운영 로그와 API 응답에서 PUBG 연동 실패 원인을 안정적으로 구분한다. */
public enum PubgApiErrorCode {
    PUBG_RATE_LIMITED,
    PUBG_TIMEOUT,
    PUBG_UNAVAILABLE,
    LOCAL_COOLDOWN,
    PUBG_ERROR
}
