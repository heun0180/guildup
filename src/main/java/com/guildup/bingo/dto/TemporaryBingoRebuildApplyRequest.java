package com.guildup.bingo.dto;

/** TEMPORARY: preview에서 발급된 snapshot token만 적용할 수 있다. */
public record TemporaryBingoRebuildApplyRequest(String previewToken) {}
