package com.guildup.announcement.repository;

import com.guildup.announcement.domain.PlatformAnnouncement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PlatformAnnouncementRepository extends JpaRepository<PlatformAnnouncement, Long> {
    // 목록/상세/알림/팝업에 동일한 공개 정책을 적용한다. GAME/COMMUNITY는 아직 공개하지 않는다.
    String VISIBLE = "a.targetType = com.guildup.announcement.domain.PlatformAnnouncementTarget.ALL "
            + "and a.published = true and (a.publishStartAt is null or a.publishStartAt <= :now) "
            + "and (a.publishEndAt is null or a.publishEndAt > :now)";
    String PRIORITY = " order by a.pinned desc, a.important desc, a.createdAt desc, a.id desc";

    @Query("select a from PlatformAnnouncement a where " + VISIBLE + PRIORITY)
    Page<PlatformAnnouncement> findVisible(@Param("now") Instant now, Pageable pageable);

    @Query("select a from PlatformAnnouncement a where a.id = :id and " + VISIBLE)
    Optional<PlatformAnnouncement> findVisibleById(@Param("id") Long id, @Param("now") Instant now);

    @Query("select a from PlatformAnnouncement a where " + VISIBLE + " order by a.createdAt desc, a.id desc")
    List<PlatformAnnouncement> findRecent(@Param("now") Instant now, Pageable pageable);

    @Query("select count(a) from PlatformAnnouncement a where " + VISIBLE
            + " and not exists (select r.id from PlatformAnnouncementRead r where r.announcement = a "
            + "and r.user.id = :userId and r.readAt is not null)")
    long countUnread(@Param("userId") Long userId, @Param("now") Instant now);

    @Query("select a from PlatformAnnouncement a where " + VISIBLE + " and a.important = true and a.popup = true "
            + "and not exists (select r.id from PlatformAnnouncementRead r where r.announcement = a "
            + "and r.user.id = :userId and r.popupConfirmedAt is not null)" + PRIORITY)
    List<PlatformAnnouncement> findPendingPopups(@Param("userId") Long userId, @Param("now") Instant now, Pageable pageable);
}
