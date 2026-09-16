package com.guildup.bingo.dto;
import java.time.Instant;
public record BingoCellCompletionResponse(String nickname, Instant completedAt) {}
