package com.guildup.community.repository;

import com.guildup.community.domain.CommunityPostComment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommunityPostCommentRepository extends JpaRepository<CommunityPostComment, Long> {
    @EntityGraph(attributePaths = {"authorCommunityUser", "authorCommunityUser.user"})
    @Query("""
            select c from CommunityPostComment c
            where c.post.id = :postId and c.post.community.id = :communityId
              and c.post.deletedAt is null and c.deletedAt is null
            order by c.createdAt asc, c.id asc
            """)
    List<CommunityPostComment> findActiveByPost(@Param("communityId") Long communityId,
                                                @Param("postId") Long postId);

    @EntityGraph(attributePaths = {"authorCommunityUser", "authorCommunityUser.user", "post"})
    @Query("""
            select c from CommunityPostComment c
            where c.id = :id and c.post.id = :postId and c.post.community.id = :communityId
              and c.post.deletedAt is null and c.deletedAt is null
            """)
    Optional<CommunityPostComment> findActive(@Param("communityId") Long communityId,
                                              @Param("postId") Long postId,
                                              @Param("id") Long id);
}
