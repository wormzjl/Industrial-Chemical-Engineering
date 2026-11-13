package com.wormzjl.icheme.science.tutorial.guide;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The field guide's equipment pages (batch 2026-11-12-tutorial-system, P5), read from {@code assets/icheme/tutorial/pages.json}
 * ({@link PageFile}): one page for each {@link EquipmentShape.Kind}, opened by its reference item and by its symbol on the
 * crude unit's map, and pages for a few of the mod's other items (the control valve). A page's id is its item's id. Its name
 * and lines are the reference item's own keys ({@link EquipmentInfo#nameKey}, {@link EquipmentInfo#pageKeys}: JEI's), so a
 * page holds no copy of them; the file adds the page's film and the screen tour it points to. The crude unit's chain
 * ({@link #unit}) is the order of the file's {@code unit} list, never the order of its keys. No Minecraft type.
 */
public record Pages(Map<String, Page> pages, List<String> unit) {
    public static final String KEY = "tutorial.icheme.page.";

    /**
     * One page: its id (an item id), its kind (null for another item), its name's key and its lines' keys, its film (null:
     * none) and the screen tour it names (a {@code tours.json} page; null: none).
     */
    public record Page(String id, EquipmentShape.@Nullable Kind kind, String nameKey, List<String> lines, @Nullable String film, @Nullable String tour) {
        public Page {
            lines = List.copyOf(lines);
        }

        /** The page of a kind, from the reference item's keys. */
        public static Page of(EquipmentShape.Kind kind, @Nullable String film, @Nullable String tour) {
            return new Page(EquipmentInfo.itemId(kind), kind, EquipmentInfo.nameKey(kind), EquipmentInfo.pageKeys(kind), film, tour);
        }

        /** The page of one of the mod's other items: its item name and its {@code .serves} (and {@code .needs}) lines. */
        public static Page item(String itemId, @Nullable String film, @Nullable String tour) {
            return new Page(itemId, null, "item.icheme." + itemId, EquipmentInfo.itemPageKeys(itemId), film, tour);
        }
    }

    public Pages {
        pages = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pages));
        unit = List.copyOf(unit);
        var seen = new HashSet<String>();
        for (var id : unit) {
            if (!pages.containsKey(id)) throw new IllegalArgumentException("The unit names no page: " + id);
            if (!seen.add(id)) throw new IllegalArgumentException("The unit names a page twice: " + id);
        }
    }

    /** The page {@code id}, or null. */
    public @Nullable Page page(String id) {
        return pages.get(id);
    }

    /** The page of {@code kind}, or null. */
    public @Nullable Page page(EquipmentShape.Kind kind) {
        return pages.get(EquipmentInfo.itemId(kind));
    }

    /** The page after {@code id} in the crude unit's chain; null at its end or for a page not in it. */
    public @Nullable Page next(String id) {
        int i = unit.indexOf(id);
        return i < 0 || i + 1 >= unit.size() ? null : pages.get(unit.get(i + 1));
    }

    /** Whether {@code id} stands in the crude unit. */
    public boolean inUnit(String id) {
        return unit.contains(id);
    }
}
