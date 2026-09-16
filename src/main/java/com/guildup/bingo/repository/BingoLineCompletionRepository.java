package com.guildup.bingo.repository;
import com.guildup.bingo.domain.BingoLineCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface BingoLineCompletionRepository extends JpaRepository<BingoLineCompletion, Long> {
    List<BingoLineCompletion> findByParticipantId(Long participantId);
    boolean existsByParticipantIdAndLineKey(Long participantId, String lineKey);
}
