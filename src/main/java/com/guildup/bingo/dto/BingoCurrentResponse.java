package com.guildup.bingo.dto;

public record BingoCurrentResponse(String type, BingoDetailResponse bingo) {
    public static BingoCurrentResponse active(BingoDetailResponse bingo) {
        return new BingoCurrentResponse("ACTIVE", bingo);
    }

    public static BingoCurrentResponse scheduled(BingoDetailResponse bingo) {
        return new BingoCurrentResponse("SCHEDULED", bingo);
    }

    public static BingoCurrentResponse none() {
        return new BingoCurrentResponse("NONE", null);
    }
}
