package com.guildup.killcompetition.dto;

import com.guildup.killcompetition.domain.KillCompetitionGameMode;
import java.time.Instant;

public record KillCompetitionCreateRequest(String title, KillCompetitionGameMode gameMode, Instant endsAt) {}
