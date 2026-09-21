package com.guildup.bingo.mission;

import java.util.*;

/** PUBG 공식 mapName과 관리자 화면 표시 이름의 대응표다. */
public final class BingoMapCatalog {
    private BingoMapCatalog() {}
    private static final Map<String, String> MAPS = Map.ofEntries(
            Map.entry("Erangel_Main", "에란겔"), Map.entry("Baltic_Main", "에란겔 (리마스터)"),
            Map.entry("Desert_Main", "미라마"), Map.entry("Savage_Main", "사녹"),
            Map.entry("DihorOtok_Main", "비켄디"), Map.entry("Tiger_Main", "태이고"),
            Map.entry("Kiki_Main", "데스턴"), Map.entry("Neon_Main", "론도"),
            Map.entry("Chimera_Main", "파라모"), Map.entry("Summerland_Main", "카라킨"),
            Map.entry("Heaven_Main", "헤이븐"), Map.entry("Range_Main", "캠프 자칼")
    );
    public static Map<String, String> maps() { return MAPS; }
    public static boolean supported(String id) { return id != null && MAPS.containsKey(id); }
    public static String displayName(String id) { return MAPS.getOrDefault(id, id); }
}
