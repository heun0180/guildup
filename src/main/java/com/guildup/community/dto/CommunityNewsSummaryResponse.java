package com.guildup.community.dto;

import java.util.List;

public record CommunityNewsSummaryResponse(List<CommunityNoticeResponse> notices,
                                            List<CommunityEventResponse> events) {}
