package com.guildup.community.repository;

import com.guildup.community.domain.Community;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CommunityRepository extends JpaRepository<Community, Long> {

    Optional<Community> findByDiscordGuildId(String discordGuildId);
}
