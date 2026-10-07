package com.guildup.announcement.dto;

import com.guildup.announcement.domain.PlatformAnnouncementType;
import java.time.Instant;

/** 작성자/읽음/대상/시스템 권한은 클라이언트가 지정할 수 없다. */
public record PlatformAnnouncementRequest(String title, String content, PlatformAnnouncementType type,
        boolean important, boolean pinned, boolean popup, boolean published,
        Instant publishStartAt, Instant publishEndAt) {}
