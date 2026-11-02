package com.wormzjl.icheme.client.gui.flowsheet;

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
            new Category("heat", List.of(new Tile("exchanger", Symbol.EXCHANGER, true), new Tile("kettle", Symbol.KETTLE, true), new Tile("air_cooler", Symbol.AIR_COOLER, true), new Tile("fired_heater", Symbol.HEATER, true))),
            new Category("machines", List.of(new Tile("pump", Symbol.PUMP, true), new Tile("compressor", Symbol.COMPRESSOR, true), new Tile("ejector", Symbol.EJECTOR, false), new Tile("steam_turbine", Symbol.TURBINE, false))),
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
            case "exchanger", "kettle", "air_cooler" -> "E";
            case "fired_heater" -> "H";
            case "pump" -> "P";
            case "compressor" -> "K";
            default -> "X";
        };
    }
}
