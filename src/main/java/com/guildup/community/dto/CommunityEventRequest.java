package com.guildup.community.dto;

import com.guildup.community.domain.CommunityEventType;
import java.time.Instant;

public record CommunityEventRequest(String title, String content, CommunityEventType type,
                                    Instant startAt, Instant endAt) {}
