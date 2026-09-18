package com.guildup.community.repository;

import com.guildup.community.domain.CommunityPost;
import com.guildup.community.domain.CommunityPostCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {
    @EntityGraph(attributePaths = {"authorCommunityUser", "authorCommunityUser.user"})
    @Query(value = """
            select p from CommunityPost p
            where p.community.id = :communityId and p.deletedAt is null
              and (:category is null or p.category = :category)
            order by p.pinned desc, p.notice desc, p.createdAt desc, p.id desc
            """, countQuery = """
            select count(p) from CommunityPost p
            where p.community.id = :communityId and p.deletedAt is null
              and (:category is null or p.category = :category)
            """)
    Page<CommunityPost> findPage(@Param("communityId") Long communityId,
                                 @Param("category") CommunityPostCategory category,
                                 Pageable pageable);

    @EntityGraph(attributePaths = {"authorCommunityUser", "authorCommunityUser.user"})
    @Query("select p from CommunityPost p where p.id = :id and p.community.id = :communityId and p.deletedAt is null")
    Optional<CommunityPost> findActive(@Param("communityId") Long communityId, @Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityPost p set p.viewCount = p.viewCount + 1 where p.id = :id and p.community.id = :communityId and p.deletedAt is null")
    int incrementViewCount(@Param("communityId") Long communityId, @Param("id") Long id);

    interface CommentCount {
        Long getPostId();
        long getCommentCount();
    }

    @Query("""
            select c.post.id as postId, count(c.id) as commentCount
            from CommunityPostComment c
            where c.post.id in :postIds and c.deletedAt is null
            group by c.post.id
            """)
    List<CommentCount> countComments(@Param("postIds") Collection<Long> postIds);
}
