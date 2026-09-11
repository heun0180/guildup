package com.guildup.community.dto;

public record CommunityNoticeRequest(String title, String content, boolean important, boolean pinned) {}
