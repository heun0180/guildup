package com.guildup.community.repository;

import com.guildup.community.domain.Community;
import org.springframework.data.jpa.repository.JpaRepository;

/** 커뮤니티 엔티티를 저장하고 조회하는 Spring Data 저장소다. */
public interface CommunityRepository extends JpaRepository<Community, Long> {
}
