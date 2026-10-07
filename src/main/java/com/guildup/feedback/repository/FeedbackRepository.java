package com.guildup.feedback.repository;

import com.guildup.feedback.domain.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    @Override @EntityGraph(attributePaths = "user")
    List<Feedback> findAll();
    @EntityGraph(attributePaths = "user")
    Page<Feedback> findByUser_Id(Long userId, Pageable pageable);
    @EntityGraph(attributePaths = "user")
    Optional<Feedback> findByIdAndUser_Id(Long id, Long userId);
    @Override @EntityGraph(attributePaths = "user")
    Page<Feedback> findAll(Pageable pageable);
}
