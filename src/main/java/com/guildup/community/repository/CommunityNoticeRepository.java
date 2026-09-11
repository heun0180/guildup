package com.guildup.community.repository;

import com.guildup.community.domain.CommunityNotice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CommunityNoticeRepository extends JpaRepository<CommunityNotice, Long> {
    @EntityGraph(attributePaths = "author")
    List<CommunityNotice> findByCommunityIdOrderByPinnedDescCreatedAtDescIdDesc(Long communityId);

    @EntityGraph(attributePaths = "author")
    List<CommunityNotice> findTop3ByCommunityIdOrderByPinnedDescCreatedAtDescIdDesc(Long communityId);

    @EntityGraph(attributePaths = "author")
    Optional<CommunityNotice> findByIdAndCommunityId(Long id, Long communityId);
}
