package com.wormzjl.icheme.science.flowsheet.sizing;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the player keeps of sizing, as facts of the sheet (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.9):
 * the design basis, and each item's mode, type, size and entries, by its id; a block's member by its block's id, a slash and its
 * own. An item with no facts is on Auto with no size, as every item of a sheet saved before sizing is. Lamps, values and paths are
 * state: a sizing rebuilds them. Every record keeps what a later version put beside its fields.
 *
 * @param basis the design basis the sheet is sized at
 * @param units by unit or member id, in the order they were written
 * @param lines by stream or member line id
 */
public record SizingFacts(DesignBasis basis, JsonObject basisUnknown, Map<String, UnitFacts> units, Map<String, LineFacts> lines, JsonObject unknown) {
    public static final SizingFacts NONE = new SizingFacts(DesignBasis.DEFAULT, new JsonObject(), Map.of(), Map.of(), new JsonObject());

    /** Whether Size all may change an item: Auto takes the proposal, Fixed keeps the player's. Saved by name. */
    public enum Mode {
        AUTO, FIXED
    }

    public SizingFacts {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(basisUnknown, "basisUnknown");
        units = Collections.unmodifiableMap(new LinkedHashMap<>(units));
        lines = Collections.unmodifiableMap(new LinkedHashMap<>(lines));
        Objects.requireNonNull(unknown, "unknown");
    }

    /**
     * A unit's or a member's facts.
     *
     * @param type its type key, null for Auto's proposal
     * @param size the size Size all gave it or the player keeps, null for none yet; a column's is its door's diameter, a valve's its
     *             inlet line's bore, so those hold none
     * @param hold a drum's entry (min) or a tank's (h), null for the row's
     */
    public record UnitFacts(Mode mode, @Nullable UnitType type, @Nullable UnitSize size, @Nullable Double hold, JsonObject sizeUnknown, JsonObject unknown) {
        public static final UnitFacts AUTO = new UnitFacts(Mode.AUTO, null, null, null, new JsonObject(), new JsonObject());

        public UnitFacts {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(sizeUnknown, "sizeUnknown");
            Objects.requireNonNull(unknown, "unknown");
        }

        public UnitFacts with(@Nullable UnitSize size) {
            return new UnitFacts(mode, type, size, hold, Objects.equals(size, this.size) ? sizeUnknown.deepCopy() : new JsonObject(), unknown.deepCopy());
        }

        public UnitFacts with(Mode mode) {
            return new UnitFacts(mode, type, size, hold, sizeUnknown.deepCopy(), unknown.deepCopy());
        }

        /**
         * These facts with the type key at {@code type}: a size of another type goes, so the next Size all proposes one of the
         * chosen type and a Fixed item is rated on it once the player gives one.
         */
        public UnitFacts typed(@Nullable UnitType type) {
            var kept = type == null || type == typeOf(size) ? size : null;
            return new UnitFacts(mode, type, kept, hold, kept == size ? sizeUnknown.deepCopy() : new JsonObject(), unknown.deepCopy());
        }

        public UnitFacts holding(@Nullable Double hold) {
            return new UnitFacts(mode, type, size, hold, sizeUnknown.deepCopy(), unknown.deepCopy());
        }

        /** What {@code size} is built as, null for a size of a class with one type. */
        public static @Nullable UnitType typeOf(@Nullable UnitSize size) {
            return switch (size) {
                case HeaterSizing.Size heater -> heater.type();
                case CompressorSizing.Size compressor -> compressor.type();
                case DrumSizing.Size drum -> drum.type();
                case TankSizing.Size tank -> tank.type();
                case null, default -> null;
            };
        }
    }

    /**
     * A line's facts.
     *
     * @param service null for what the stream says
     * @param length  m, null for the basis's
     * @param size    null for none yet
     */
    public record LineFacts(Mode mode, @Nullable Service service, @Nullable Double length, LineSizing.@Nullable Size size, JsonObject sizeUnknown, JsonObject unknown) {
        public static final LineFacts AUTO = new LineFacts(Mode.AUTO, null, null, null, new JsonObject(), new JsonObject());

        public LineFacts {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(sizeUnknown, "sizeUnknown");
            Objects.requireNonNull(unknown, "unknown");
        }

        public LineFacts with(LineSizing.@Nullable Size size) {
            return new LineFacts(mode, service, length, size, Objects.equals(size, this.size) ? sizeUnknown.deepCopy() : new JsonObject(), unknown.deepCopy());
        }

        public LineFacts with(Mode mode) {
            return new LineFacts(mode, service, length, size, sizeUnknown.deepCopy(), unknown.deepCopy());
        }

        public LineFacts serving(@Nullable Service service) {
            return new LineFacts(mode, service, length, size, sizeUnknown.deepCopy(), unknown.deepCopy());
        }

        public LineFacts lasting(@Nullable Double length) {
            return new LineFacts(mode, service, length, size, sizeUnknown.deepCopy(), unknown.deepCopy());
        }
    }

    public UnitFacts unit(String id) {
        return units.getOrDefault(id, UnitFacts.AUTO);
    }

    public LineFacts line(String id) {
        return lines.getOrDefault(id, LineFacts.AUTO);
    }

    /** These facts with {@code id}'s at {@code facts}; Auto's own facts, with nothing kept, remove the row. */
    public SizingFacts with(String id, UnitFacts facts) {
        var units = new LinkedHashMap<>(this.units);
        if (facts.equals(UnitFacts.AUTO)) units.remove(id);
        else units.put(id, facts);
        return new SizingFacts(basis, basisUnknown.deepCopy(), units, lines, unknown.deepCopy());
    }

    /** These facts with line {@code id}'s at {@code facts}. */
    public SizingFacts with(String id, LineFacts facts) {
        var lines = new LinkedHashMap<>(this.lines);
        if (facts.equals(LineFacts.AUTO)) lines.remove(id);
        else lines.put(id, facts);
        return new SizingFacts(basis, basisUnknown.deepCopy(), units, lines, unknown.deepCopy());
    }

    public SizingFacts with(DesignBasis basis) {
        return new SizingFacts(basis, basisUnknown.deepCopy(), units, lines, unknown.deepCopy());
    }

    /** Whether a sheet holding these needs to write them: anything but a sheet that was never sized. */
    public boolean any() {
        return !basis.equals(DesignBasis.DEFAULT) || basisUnknown.size() > 0 || !units.isEmpty() || !lines.isEmpty() || unknown.size() > 0;
    }

    /** A copy that shares nothing mutable. */
    public SizingFacts copy() {
        var units = new LinkedHashMap<String, UnitFacts>();
        this.units.forEach((id, facts) -> units.put(id, new UnitFacts(facts.mode(), facts.type(), facts.size(), facts.hold(), facts.sizeUnknown().deepCopy(), facts.unknown().deepCopy())));
        var lines = new LinkedHashMap<String, LineFacts>();
        this.lines.forEach((id, facts) -> lines.put(id, new LineFacts(facts.mode(), facts.service(), facts.length(), facts.size(), facts.sizeUnknown().deepCopy(), facts.unknown().deepCopy())));
        return new SizingFacts(basis, basisUnknown.deepCopy(), units, lines, unknown.deepCopy());
    }
}
