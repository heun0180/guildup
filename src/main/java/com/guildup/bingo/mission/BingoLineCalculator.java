package com.guildup.bingo.mission;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class BingoLineCalculator {
    public Set<String> completedLines(int size, Set<Integer> completedPositions) {
        Set<String> lines = new LinkedHashSet<>();
        for (int row = 0; row < size; row++) {
            boolean complete = true;
            for (int col = 0; col < size; col++) complete &= completedPositions.contains(row * size + col);
            if (complete) lines.add("ROW_" + row);
        }
        for (int col = 0; col < size; col++) {
            boolean complete = true;
            for (int row = 0; row < size; row++) complete &= completedPositions.contains(row * size + col);
            if (complete) lines.add("COL_" + col);
        }
        boolean diagonal = true, reverse = true;
        for (int i = 0; i < size; i++) {
            diagonal &= completedPositions.contains(i * size + i);
            reverse &= completedPositions.contains(i * size + (size - i - 1));
        }
        if (diagonal) lines.add("DIAG_MAIN");
        if (reverse) lines.add("DIAG_ANTI");
        return Set.copyOf(lines);
    }
}
