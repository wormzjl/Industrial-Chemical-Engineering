package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The palette (batch 2026-11-02-process-simulator, plan sections 3.2 and 3.5): its categories and tiles, one for each function.
 * A lit tile has a process model on day one; an unlit one is drawn so the list stays whole, and its hover says why it is off. A tile's
 * {@code type} is the stable string a flowsheet file holds, or {@link #FEED} or {@link #PRODUCT}, which draw a stream, not a unit.
 * The list is public data, so the coverage gate of W9 can read it. Texts are keys under {@code gui.icheme.}.
 */
public final class Tiles {
    public static final String KEY = "flowsheet.", FEED = "feed", PRODUCT = "product";

    /** A tile: its stable type, its symbol, whether it has a process model. */
    public record Tile(String type, Symbol symbol, boolean lit) {
        /** Its name's key. */
        public String name() {
            return KEY + "tile." + type;
        }
    }

    public record Category(String id, List<Tile> tiles) {
        public Category {
            tiles = List.copyOf(tiles);
        }

        public String name() {
            return KEY + "category." + id;
        }
    }

    public static final List<Category> CATEGORIES = List.of(
            new Category("streams", List.of(new Tile(FEED, Symbol.FEED, true), new Tile(PRODUCT, Symbol.PRODUCT, true))),
            new Category("piping", List.of(new Tile("valve", Symbol.VALVE, true), new Tile("mixer", Symbol.MIXER, true), new Tile("splitter", Symbol.SPLITTER, true), new Tile("filter", Symbol.FILTER, true))),
            new Category("vessels", List.of(new Tile("column", Symbol.COLUMN_TOP, true), new Tile("drum", Symbol.DRUM, true), new Tile("storage_tank", Symbol.TANK, true), new Tile("adsorber", Symbol.ADSORBER, false), new Tile("cyclone", Symbol.CYCLONE, false))),
            new Category("heat", List.of(new Tile("exchanger", Symbol.EXCHANGER, true), new Tile("kettle", Symbol.KETTLE, true), new Tile("cooler", Symbol.AIR_COOLER, true), new Tile("fired_heater", Symbol.HEATER, true), new Tile("cooling_tower", Symbol.COOLING_TOWER, false))),
            new Category("machines", List.of(new Tile("pump", Symbol.PUMP, true), new Tile("compressor", Symbol.COMPRESSOR, true), new Tile("ejector", Symbol.EJECTOR, true), new Tile("steam_turbine", Symbol.TURBINE, false))),
            new Category("reactors", List.of(new Tile("stirred_reactor", Symbol.REACTOR, false), new Tile("trickle_bed", Symbol.TRICKLE, false), new Tile("ebullated_bed", Symbol.EBULLATED, false), new Tile("fcc_converter", Symbol.FCC, false), new Tile("delayed_coker", Symbol.COKER, false))),
            new Category("solid_fuel", List.of(new Tile("cfb_boiler", Symbol.CFB, false), new Tile("fluid_bed_gasifier", Symbol.FLUID_GAS, false), new Tile("grate_boiler", Symbol.GRATE, false), new Tile("fixed_bed_gasifier", Symbol.FIXED_GAS, false))));

    private Tiles() {
    }

    public static List<Tile> all() {
        var all = new ArrayList<Tile>();
        for (var category : CATEGORIES) all.addAll(category.tiles());
        return all;
    }

    /** The tile of {@code type}, or null: a removed type has none. */
    public static @Nullable Tile tile(String type) {
        for (var tile : all()) if (tile.type().equals(type)) return tile;
        return null;
    }

    /** The symbol of a unit of {@code type}, or null where this version has none: a placeholder then stands in. A column's is its top slice. */
    public static @Nullable Symbol symbol(String type) {
        var tile = tile(type);
        return tile == null ? null : tile.symbol();
    }

    /**
     * A unit's symbol: its tile's, until the sizing holds its type (plan section 3.10), the type the player keeps or the one its
     * size is built as. A lying drum with a boot shows the boot.
     */
    public static @Nullable Symbol symbol(Flowsheet.Unit unit, SizingFacts sizing) {
        var tile = symbol(unit.type());
        if (tile == null) return null;
        var facts = sizing.unit(unit.id());
        var type = facts.type() != null ? facts.type() : SizingFacts.UnitFacts.typeOf(facts.size());
        if (!builtAs(unit.type(), type)) return tile;
        return switch (type) {
            case VERTICAL_HEATER -> Symbol.HEATER;
            case CABIN_HEATER -> Symbol.CABIN;
            case FLOATING_ROOF -> Symbol.TANK;
            case SPHERE -> Symbol.SPHERE;
            case STANDING_DRUM -> Symbol.DRUM;
            case LYING_DRUM -> facts.size() instanceof DrumSizing.Size drum && drum.boot() ? Symbol.BOOT_DRUM : Symbol.LYING;
            case PISTON -> Symbol.COMPRESSOR;
            case TURBO -> Symbol.TURBO;
            // The Cooler's two (C2): on Water it is drawn as the exchanger it is (owner, 2026-11-03).
            case AIR_COOLER -> Symbol.AIR_COOLER;
            case WATER_COOLER -> Symbol.EXCHANGER;
        };
    }

    /** The types each typed tile is built as. */
    private static final java.util.Map<String, List<UnitType>> TYPED = java.util.Map.of(
            "fired_heater", List.of(UnitType.VERTICAL_HEATER, UnitType.CABIN_HEATER),
            "storage_tank", List.of(UnitType.FLOATING_ROOF, UnitType.SPHERE),
            "drum", List.of(UnitType.STANDING_DRUM, UnitType.LYING_DRUM),
            "compressor", List.of(UnitType.PISTON, UnitType.TURBO),
            "cooler", List.of(UnitType.AIR_COOLER, UnitType.WATER_COOLER));

    /** The types tile {@code tile} is built as (plan section 3.10), none for an untyped tile; the Sizing tab's type keys. */
    public static List<UnitType> types(@Nullable String tile) {
        return TYPED.getOrDefault(tile, List.of());
    }

    /** Whether a unit of tile {@code tile} can be built as {@code type}: an exchanger's size is no Water cooler on an exchanger tile. */
    public static boolean builtAs(@Nullable String tile, @Nullable UnitType type) {
        return type != null && TYPED.getOrDefault(tile, List.of()).contains(type);
    }

    /** The name's key of a unit of {@code type}, a removed one's too. */
    /** A removed type's own name, as a person would write it. */
    public static String typeName(String type) {
        String text = type.replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public static String name(String type) {
        return tile(type) == null ? KEY + "tile.removed" : tile(type).name();
    }

    /** The prefix of a tag; the next free number follows it. */
    public static String prefix(String type) {
        return switch (type) {
            case "valve" -> "V";
            case "mixer" -> "M";
            case "splitter" -> "TEE";
            case "filter" -> "F";
            case "column" -> "T";
            case "drum" -> "D";
            case "storage_tank" -> "TK";
            case "exchanger", "kettle", "cooler" -> "E";
            case "fired_heater" -> "H";
            case "pump" -> "P";
            case "compressor" -> "K";
            case "ejector" -> "J";
            default -> "X";
        };
    }
}
