package com.guildup.bingo.dto;

import com.guildup.bingo.mission.BingoItemCatalog;
import com.guildup.bingo.mission.BingoMapCatalog;
import com.guildup.bingo.mission.BingoWeaponCatalog;
import java.util.*;

public record BingoCatalogResponse(List<Item> items, Map<String, String> maps, List<Weapon> weapons) {
    public static BingoCatalogResponse current() {
        return new BingoCatalogResponse(BingoItemCatalog.items().stream()
                .map(item -> new Item(item.id(), item.name(), item.category(), item.usable())).toList(),
                BingoMapCatalog.maps(), BingoWeaponCatalog.weapons().stream()
                .map(weapon -> new Weapon(weapon.canonicalName(), weapon.displayName(), weapon.category())).toList());
    }
    public record Item(String id, String name, String category, boolean usable) {}
    public record Weapon(String canonicalName, String displayName, String category) {}
}
