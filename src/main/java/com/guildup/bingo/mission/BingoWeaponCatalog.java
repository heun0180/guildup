package com.guildup.bingo.mission;

import java.util.*;

/** PUBG 공식 damageCauserName을 빙고용 canonical 무기명으로 변환한다. */
public final class BingoWeaponCatalog {
    private BingoWeaponCatalog() {}

    public record Weapon(String canonicalName, String displayName, String category,
                         List<String> telemetryNames) {}

    private static final List<Weapon> WEAPONS = List.of(
            weapon("ACE32", "ACE32", "AR", "WeapACE32_C"),
            weapon("AKM", "AKM", "AR", "WeapAK47_C", "WeapLunchmeatsAK47_C"),
            weapon("AUG", "AUG", "AR", "WeapAUG_C", "AUG A3"),
            weapon("BerylM762", "Beryl M762", "AR", "WeapBerylM762_C", "Beryl"),
            weapon("G36C", "G36C", "AR", "WeapG36C_C"),
            weapon("Groza", "Groza", "AR", "WeapGroza_C"),
            weapon("K2", "K2", "AR", "WeapK2_C"),
            weapon("M16A4", "M16A4", "AR", "WeapM16A4_C"),
            weapon("M416", "M416", "AR", "WeapHK416_C", "WeapDuncansHK416_C", "HK416"),
            weapon("Mk47Mutant", "Mk47 Mutant", "AR", "WeapMk47Mutant_C"),
            weapon("QBZ95", "QBZ95", "AR", "WeapQBZ95_C", "QBZ"),
            weapon("SCAR-L", "SCAR-L", "AR", "WeapSCAR-L_C"),

            weapon("Dragunov", "Dragunov", "DMR", "WeapDragunov_C"),
            weapon("Mini14", "Mini14", "DMR", "WeapMini14_C", "Mini 14"),
            weapon("Mk12", "Mk12", "DMR", "WeapMk12_C"),
            weapon("Mk14", "Mk14", "DMR", "WeapMk14_C", "Mk14 EBR"),
            weapon("QBU88", "QBU88", "DMR", "WeapQBU88_C", "WeapMadsQBU88_C", "QBU"),
            weapon("SKS", "SKS", "DMR", "WeapSKS_C"),
            weapon("SLR", "SLR", "DMR", "WeapFNFal_C"),
            weapon("VSS", "VSS", "DMR", "WeapVSS_C"),

            weapon("AWM", "AWM", "SR", "WeapAWM_C"),
            weapon("Kar98k", "Kar98k", "SR", "WeapKar98k_C", "WeapJuliesKar98k_C"),
            weapon("LynxAMR", "Lynx AMR", "SR", "WeapL6_C"),
            weapon("M24", "M24", "SR", "WeapM24_C"),
            weapon("MosinNagant", "Mosin-Nagant", "SR", "WeapMosinNagant_C"),
            weapon("Win94", "Win94", "SR", "WeapWin94_C"),

            weapon("Bizon", "Bizon", "SMG", "WeapBizonPP19_C"),
            weapon("JS9", "JS9", "SMG", "WeapJS9_C"),
            weapon("MP5K", "MP5K", "SMG", "WeapMP5K_C"),
            weapon("MP9", "MP9", "SMG", "WeapMP9_C"),
            weapon("MicroUZI", "Micro Uzi", "SMG", "WeapUZI_C"),
            weapon("P90", "P90", "SMG", "WeapP90_C"),
            weapon("TommyGun", "Tommy Gun", "SMG", "WeapThompson_C"),
            weapon("UMP9", "UMP9", "SMG", "WeapUMP_C", "UMP45"),
            weapon("Vector", "Vector", "SMG", "WeapVector_C"),

            weapon("DBS", "DBS", "Shotgun", "WeapDP12_C"),
            weapon("O12", "O12", "Shotgun", "WeapOriginS12_C"),
            weapon("S12K", "S12K", "Shotgun", "WeapSaiga12_C"),
            weapon("S1897", "S1897", "Shotgun", "WeapWinchester_C"),
            weapon("S686", "S686", "Shotgun", "WeapBerreta686_C"),
            weapon("SawedOff", "Sawed-off", "Shotgun", "WeapSawnoff_C"),

            weapon("DP28", "DP-28", "LMG", "WeapDP28_C"),
            weapon("M249", "M249", "LMG", "WeapM249_C"),
            weapon("MG3", "MG3", "LMG", "WeapMG3_C"),

            weapon("Deagle", "Deagle", "Handgun", "WeapDesertEagle_C"),
            weapon("P18C", "P18C", "Handgun", "WeapG18_C"),
            weapon("P1911", "P1911", "Handgun", "WeapM1911_C"),
            weapon("P92", "P92", "Handgun", "WeapM9_C"),
            weapon("R1895", "R1895", "Handgun", "WeapNagantM1895_C"),
            weapon("R45", "R45", "Handgun", "WeapRhino_C"),
            weapon("Skorpion", "Skorpion", "Handgun", "Weapvz61Skorpion_C"),

            weapon("Crossbow", "Crossbow", "Crossbow", "WeapCrossbow_1_C"),
            weapon("Crowbar", "Crowbar", "Melee", "WeapCowbar_C", "WeapCowbarProjectile_C"),
            weapon("Machete", "Machete", "Melee", "WeapMachete_C", "WeapMacheteProjectile_C"),
            weapon("Pan", "Pan", "Melee", "WeapPan_C", "WeapPanProjectile_C"),
            weapon("Sickle", "Sickle", "Melee", "WeapSickle_C", "WeapSickleProjectile_C"),
            weapon("Panzerfaust", "Panzerfaust", "Special", "WeapPanzerFaust100M1_C", "PanzerFaust100M_Projectile_C")
    );

    private static final Map<String, Weapon> BY_ALIAS;
    private static final Map<String, Weapon> BY_CANONICAL;
    static {
        Map<String, Weapon> aliases = new LinkedHashMap<>();
        Map<String, Weapon> canonicals = new LinkedHashMap<>();
        for (Weapon weapon : WEAPONS) {
            canonicals.put(key(weapon.canonicalName()), weapon);
            aliases.put(key(weapon.canonicalName()), weapon);
            aliases.put(key(weapon.displayName()), weapon);
            weapon.telemetryNames().forEach(alias -> aliases.put(key(alias), weapon));
        }
        BY_ALIAS = Map.copyOf(aliases);
        BY_CANONICAL = Map.copyOf(canonicals);
    }

    private static Weapon weapon(String canonical, String display, String category, String... telemetryNames) {
        return new Weapon(canonical, display, category, List.of(telemetryNames));
    }

    public static List<Weapon> weapons() { return WEAPONS; }

    public static String canonicalName(String raw) {
        Weapon weapon = BY_ALIAS.get(key(raw));
        return weapon == null ? null : weapon.canonicalName();
    }

    public static boolean supported(String raw) { return canonicalName(raw) != null; }

    public static String category(String raw) {
        String canonical = canonicalName(raw);
        Weapon weapon = canonical == null ? null : BY_CANONICAL.get(key(canonical));
        return weapon == null ? null : weapon.category();
    }

    public static String normalize(String raw) {
        String canonical = canonicalName(raw);
        if (canonical != null) return key(canonical);
        String value = key(raw);
        if (value.contains("molotov")) return "molotov";
        if (value.contains("smoke")) return "smokebomb";
        if (value.contains("flash") || value.contains("stun")) return "flashbang";
        if (value.contains("grenade")) return "fraggrenade";
        return value;
    }

    public static boolean same(String expected, String actual) {
        String expectedCanonical = canonicalName(expected);
        String actualCanonical = canonicalName(actual);
        if (expectedCanonical != null || actualCanonical != null) {
            return expectedCanonical != null && expectedCanonical.equals(actualCanonical);
        }
        return normalize(expected).equals(normalize(actual));
    }

    public static List<String> supportedWeapons() {
        return WEAPONS.stream().map(Weapon::canonicalName).toList();
    }

    private static String key(String raw) {
        if (raw == null) return "";
        return raw.trim().replace("Item_Weapon_", "").replace("Weap", "")
                .replace("_C", "").replace("_", "").replace("-", "")
                .replace(" ", "").toLowerCase(Locale.ROOT);
    }
}
