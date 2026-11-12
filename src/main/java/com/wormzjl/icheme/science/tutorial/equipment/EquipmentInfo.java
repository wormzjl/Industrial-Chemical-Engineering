package com.wormzjl.icheme.science.tutorial.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The equipment reference items (batch 2026-11-12-tutorial-system, P3; owner decision D8, O1 reference only): one item
 * per {@link EquipmentShape.Kind}, its id, its name key and its lines, and which kinds have no working model yet. No
 * Minecraft type, so the gates read it without a bootstrap.
 *
 * <p>The item ids are registry names in saves: a {@code Kind} constant renamed later needs an item alias
 * ({@code ModItems.ITEMS.addAlias}) from its old id.
 */
public final class EquipmentInfo {
    /** The item ids' prefix: {@code equipment_<kind in lower case>}. */
    public static final String PREFIX = "equipment_";
    /** The shared tooltip line under every reference item. */
    public static final String REFERENCE_KEY = "tutorial.icheme.equipment_info.reference";
    /** The fourth line of a kind with no model in the world or the simulator: it is drawn and built, nothing more. */
    public static final String SHAPE_ONLY_KEY = "tutorial.icheme.equipment_info.shape_only";
    /** The fourth line of a kind that works in the world while the process simulator only draws it. */
    public static final String SIMULATOR_SHAPE_KEY = "tutorial.icheme.equipment_info.simulator_shape";

    /**
     * Kinds with no working model, in the world or in the simulator: the simulator draws them (DRAWING_ONLY) and the
     * world builds their shape, but no fluid, heat or reaction passes through them yet.
     */
    public static final Set<EquipmentShape.Kind> SHAPE_ONLY = Set.copyOf(EnumSet.of(EquipmentShape.Kind.STIRRED_REACTOR, EquipmentShape.Kind.ADSORBER,
            EquipmentShape.Kind.TRICKLE_BED_REACTOR, EquipmentShape.Kind.EBULLATED_BED_REACTOR, EquipmentShape.Kind.CYCLONE, EquipmentShape.Kind.FCC_CONVERTER,
            EquipmentShape.Kind.DELAYED_COKER, EquipmentShape.Kind.FLUIDIZED_BED_BOILER, EquipmentShape.Kind.FLUIDIZED_BED_GASIFIER, EquipmentShape.Kind.GRATE_BOILER,
            EquipmentShape.Kind.FIXED_BED_GASIFIER));

    /**
     * Kinds that work in the world (the cooling towers as reservoirs that cool their water, the drivers on the shaft
     * trains, the converters as a vessel with two hidden ports) while the process simulator draws them only (its
     * catalog's DRAWING_ONLY).
     */
    public static final Set<EquipmentShape.Kind> SIMULATOR_SHAPE = Set.copyOf(EnumSet.of(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,
            EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER, EquipmentShape.Kind.MOTOR, EquipmentShape.Kind.GENERATOR, EquipmentShape.Kind.GEARBOX,
            EquipmentShape.Kind.FLUID_CONVERTER, EquipmentShape.Kind.GAS_CONVERTER));

    /** The ids of the mod's other items that need something to work (a {@code .needs} line beside their {@code .serves}). */
    public static final Set<String> ITEMS_THAT_NEED = Set.of("fluid_pump", "fluid_compressor", "local_controller", "dcs", "gas_blanketing_valve", "create_coupling");

    private EquipmentInfo() {
    }

    /** The kind's item id: {@code equipment_<kind>}. */
    public static String itemId(EquipmentShape.Kind kind) {
        return PREFIX + kind.name().toLowerCase(Locale.ROOT);
    }

    /** Every kind's item id, in {@code Kind} order: the registration loop's list. */
    public static List<String> itemIds() {
        var out = new ArrayList<String>();
        for (var kind : EquipmentShape.Kind.values()) out.add(itemId(kind));
        return List.copyOf(out);
    }

    /**
     * The kind's name: the equipment constructor's name where one constructor entry is this kind (a floating-roof tank
     * for {@code TANK}), a key of its own where two are ({@code DRUM}: vertical and horizontal).
     */
    public static String nameKey(EquipmentShape.Kind kind) {
        return switch (kind) {
            case DRUM -> "item.icheme." + itemId(kind);
            case TANK -> "gui.icheme.equipment_constructor.equipment.floating_roof_tank";
            default -> "gui.icheme.equipment_constructor.equipment." + kind.name().toLowerCase(Locale.ROOT);
        };
    }

    private static String key(EquipmentShape.Kind kind, String line) {
        return "tutorial.icheme.equipment." + kind.name().toLowerCase(Locale.ROOT) + "." + line;
    }

    /** What it does to the stream. */
    public static String servesKey(EquipmentShape.Kind kind) {
        return key(kind, "serves");
    }

    /** What it needs: heat, cooling water, power, steam, fuel, or nothing but pipes. */
    public static String needsKey(EquipmentShape.Kind kind) {
        return key(kind, "needs");
    }

    /** Where it stands in a plant: what comes before and after. */
    public static String placeKey(EquipmentShape.Kind kind) {
        return key(kind, "place");
    }

    /** The fourth line, or null: shape only, or working in the world but drawn only in the simulator. */
    public static String statusKey(EquipmentShape.Kind kind) {
        if (SHAPE_ONLY.contains(kind)) return SHAPE_ONLY_KEY;
        if (SIMULATOR_SHAPE.contains(kind)) return SIMULATOR_SHAPE_KEY;
        return null;
    }

    /** The lines of a kind's information page, in order: serves, needs, place and its fourth line where it has one. */
    public static List<String> pageKeys(EquipmentShape.Kind kind) {
        var out = new ArrayList<>(List.of(servesKey(kind), needsKey(kind), placeKey(kind)));
        var status = statusKey(kind);
        if (status != null) out.add(status);
        return List.copyOf(out);
    }

    /** The kind of a reference item id, or null for another item. */
    public static EquipmentShape.Kind kindOf(String itemId) {
        for (var kind : EquipmentShape.Kind.values()) if (itemId(kind).equals(itemId)) return kind;
        return null;
    }

    /** The page lines of one of the mod's other items: its {@code .serves}, and its {@code .needs} where it needs something. */
    public static List<String> itemPageKeys(String itemId) {
        var serves = "tutorial.icheme.item." + itemId + ".serves";
        return ITEMS_THAT_NEED.contains(itemId) ? List.of(serves, "tutorial.icheme.item." + itemId + ".needs") : List.of(serves);
    }
}
