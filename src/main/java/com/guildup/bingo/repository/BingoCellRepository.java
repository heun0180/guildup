package com.guildup.bingo.repository;
import com.guildup.bingo.domain.BingoCell;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BingoCellRepository extends JpaRepository<BingoCell, Long> {}
