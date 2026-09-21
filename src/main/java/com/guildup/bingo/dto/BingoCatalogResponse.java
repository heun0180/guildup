package com.guildup.bingo.dto;

import com.guildup.bingo.mission.BingoItemCatalog;
import com.guildup.bingo.mission.BingoMapCatalog;
import java.util.*;

public record BingoCatalogResponse(List<Item> items, Map<String, String> maps) {
    public static BingoCatalogResponse current() {
        return new BingoCatalogResponse(BingoItemCatalog.items().stream()
                .map(item -> new Item(item.id(), item.name(), item.category(), item.usable())).toList(),
                BingoMapCatalog.maps());
    }
    public record Item(String id, String name, String category, boolean usable) {}
}
