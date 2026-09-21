package com.guildup.bingo.mission;

import java.util.*;

/** PUBG 공식 itemId와 관리자 화면 표시 이름을 한 곳에서 관리한다. */
public final class BingoItemCatalog {
    private BingoItemCatalog() {}

    public record Item(String id, String name, String category, boolean usable) {}
    private static final List<Item> ITEMS = List.of(
            new Item("Item_Weapon_HK416_C", "M416", "무기", false),
            new Item("Item_Weapon_BerylM762_C", "Beryl M762", "무기", false),
            new Item("Item_Weapon_AWM_C", "AWM", "무기", false),
            new Item("Item_Weapon_Kar98k_C", "Kar98k", "무기", false),
            new Item("Item_Weapon_M24_C", "M24", "무기", false),
            new Item("Item_Weapon_AUG_C", "AUG", "무기", false),
            new Item("Item_Weapon_Mk14_C", "Mk14 EBR", "무기", false),
            new Item("Item_Heal_Bandage_C", "붕대", "회복", true),
            new Item("Item_Heal_FirstAid_C", "구급상자", "회복", true),
            new Item("Item_Heal_MedKit_C", "의료용 키트", "회복", true),
            new Item("Item_Boost_EnergyDrink_C", "에너지 드링크", "부스트", true),
            new Item("Item_Boost_PainKiller_C", "진통제", "부스트", true),
            new Item("Item_Boost_AdrenalineSyringe_C", "아드레날린 주사기", "부스트", true),
            new Item("Item_Head_E_01_Lv1_C", "1레벨 헬멧", "장비", false),
            new Item("Item_Head_F_01_Lv2_C", "2레벨 헬멧", "장비", false),
            new Item("Item_Head_G_01_Lv3_C", "3레벨 헬멧", "장비", false),
            new Item("Item_Armor_E_01_Lv1_C", "1레벨 조끼", "장비", false),
            new Item("Item_Armor_D_01_Lv2_C", "2레벨 조끼", "장비", false),
            new Item("Item_Armor_C_01_Lv3_C", "3레벨 조끼", "장비", false),
            new Item("Item_Back_E_01_Lv1_C", "1레벨 배낭", "장비", false),
            new Item("Item_Back_F_01_Lv2_C", "2레벨 배낭", "장비", false),
            new Item("Item_Back_C_01_Lv3_C", "3레벨 배낭", "장비", false),
            new Item("Item_Tiger_SelfRevive_C", "자가제세동기", "특수", true),
            new Item("Item_EmergencyPickup_C", "비상호출", "특수", true),
            new Item("Item_Ghillie_01_C", "길리슈트", "특수", false)
    );
    private static final Map<String, Item> BY_ID;
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("Item_Weapon_Duncans_M416_C", "Item_Weapon_HK416_C"),
            Map.entry("Item_Head_E_02_Lv1_C", "Item_Head_E_01_Lv1_C"),
            Map.entry("Item_Head_F_02_Lv2_C", "Item_Head_F_01_Lv2_C"),
            Map.entry("Item_Back_E_02_Lv1_C", "Item_Back_E_01_Lv1_C"),
            Map.entry("Item_Back_F_02_Lv2_C", "Item_Back_F_01_Lv2_C"),
            Map.entry("Item_Back_C_02_Lv3_C", "Item_Back_C_01_Lv3_C"),
            Map.entry("Item_Back_B_08_Lv3_C", "Item_Back_C_01_Lv3_C"),
            Map.entry("Item_Ghillie_02_C", "Item_Ghillie_01_C"), Map.entry("Item_Ghillie_03_C", "Item_Ghillie_01_C"),
            Map.entry("Item_Ghillie_04_C", "Item_Ghillie_01_C"), Map.entry("Item_Ghillie_05_C", "Item_Ghillie_01_C"),
            Map.entry("Item_Ghillie_06_C", "Item_Ghillie_01_C"), Map.entry("Item_Ghillie_07_C", "Item_Ghillie_01_C")
    );
    static {
        Map<String, Item> values = new LinkedHashMap<>();
        ITEMS.forEach(item -> values.put(item.id(), item));
        BY_ID = Map.copyOf(values);
    }

    public static List<Item> items() { return ITEMS; }
    public static List<Item> usableItems() { return ITEMS.stream().filter(Item::usable).toList(); }
    public static boolean supported(String id) { return id != null && BY_ID.containsKey(id); }
    public static boolean usable(String id) { Item item = BY_ID.get(id); return item != null && item.usable(); }
    public static String displayName(String id) { Item item = BY_ID.get(id); return item == null ? id : item.name(); }
    public static String canonicalId(String id) { return id == null ? null : ALIASES.getOrDefault(id, id); }
}
