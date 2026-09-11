package com.guildup.community.repository;

import com.guildup.community.domain.CommunityEvent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CommunityEventRepository extends JpaRepository<CommunityEvent, Long> {
    // 진행 중/예정 이벤트는 가까운 시작 순, 종료 이벤트는 최근 시작 순으로 뒤에 표시한다.
    @EntityGraph(attributePaths = "author")
    @Query("""
            select e from CommunityEvent e where e.community.id = :communityId
            order by case when e.endAt < :now then 1 else 0 end,
                     case when e.endAt >= :now then e.startAt else null end asc,
                     e.startAt desc, e.id desc
            """)
    List<CommunityEvent> findFeed(@Param("communityId") Long communityId, @Param("now") Instant now);

    @EntityGraph(attributePaths = "author")
    List<CommunityEvent> findTop3ByCommunityIdAndStartAtGreaterThanEqualOrderByStartAtAscIdAsc(
            Long communityId, Instant now);

    @EntityGraph(attributePaths = "author")
    Optional<CommunityEvent> findByIdAndCommunityId(Long id, Long communityId);
}
