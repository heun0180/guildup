package com.guildup.community.repository;

import com.guildup.community.domain.Community;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 커뮤니티 엔티티를 저장하고 조회하는 Spring Data 저장소다. */
public interface CommunityRepository extends JpaRepository<Community, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select community from Community community where community.id = :id")
    Optional<Community> findForUpdate(@Param("id") Long id);
}
