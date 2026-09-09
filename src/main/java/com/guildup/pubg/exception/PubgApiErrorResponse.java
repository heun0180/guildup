package com.guildup.pubg.exception;

/** 프론트엔드에 노출할 PUBG 연동 오류 응답이다. */
public record PubgApiErrorResponse(int status, String message) {}
