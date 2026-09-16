package com.guildup.bingo.mission;

import java.util.*;

/** Telemetry itemId/damageCauserName 정규화와 무기 분류를 한 곳에서 관리한다. */
public final class BingoWeaponCatalog {
    private BingoWeaponCatalog() {}
    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();
    static {
        register("AR", "AKM", "AUG", "BerylM762", "G36C", "Groza", "K2", "M16A4", "M416", "Mk47Mutant", "QBZ", "SCAR-L");
        register("DMR", "Dragunov", "Mini14", "Mk12", "Mk14", "QBU", "SKS", "SLR", "VSS");
        register("SR", "AWM", "Kar98k", "LynxAMR", "M24", "MosinNagant", "Win94");
        register("SMG", "Bizon", "JS9", "MP5K", "MP9", "MicroUZI", "P90", "TommyGun", "UMP45", "Vector");
        register("Shotgun", "DBS", "O12", "S12K", "S1897", "S686", "SawedOff");
        register("LMG", "DP28", "M249", "MG3");
        register("Handgun", "Deagle", "P18C", "P1911", "P92", "R1895", "R45", "Skorpion");
        register("Crossbow", "Crossbow");
        register("Melee", "Crowbar", "Machete", "Pan", "Sickle");
    }
    private static void register(String category, String... names) {
        for (String name : names) CATEGORIES.put(normalize(name), category);
    }
    public static String category(String raw) { return CATEGORIES.get(normalize(raw)); }
    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.replace("Item_Weapon_", "").replace("Weap", "")
                .replace("_C", "").replace("_", "").replace("-", "");
        value = value.toLowerCase(Locale.ROOT);
        if (value.contains("molotov")) return "molotov";
        if (value.contains("smoke")) return "smokebomb";
        if (value.contains("flash") || value.contains("stun")) return "flashbang";
        if (value.contains("grenade")) return "fraggrenade";
        if (value.equals("hk416")) return "m416";
        return value;
    }
    public static boolean same(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }
    public static List<String> supportedWeapons() {
        return CATEGORIES.keySet().stream().sorted().toList();
    }
}
