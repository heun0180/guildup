package com.guildup.announcement.repository;

import com.guildup.announcement.domain.PlatformAnnouncementRead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlatformAnnouncementReadRepository extends JpaRepository<PlatformAnnouncementRead, Long> {
    Optional<PlatformAnnouncementRead> findByAnnouncement_IdAndUser_Id(Long announcementId, Long userId);
    List<PlatformAnnouncementRead> findByUser_IdAndAnnouncement_IdIn(Long userId, Collection<Long> announcementIds);
}
