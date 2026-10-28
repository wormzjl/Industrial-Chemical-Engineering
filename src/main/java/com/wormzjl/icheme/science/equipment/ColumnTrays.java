package com.wormzjl.icheme.science.equipment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The tray layout a distillation column's structure is built from (owner request 2026-10-16, batch
 * 2026-10-15-custom-equipment-rendering): the interior trays at the column solver's 0.6 m spacing, numbered from the top
 * as in the column calculator (tray 1 under the condenser, stage {@code trays + 1} the sump), the feed tray, side draws,
 * steam stages and pumparounds. A design imported from a column calculator keeps its numbers.
 *
 * <p>{@code components}: the component design a column calculator saved with these trays (batch
 * 2026-10-26-multi-machine-framework, plan section 4.5), or null for a column built without one, which is every column
 * built before the field existed. Saved by name beside the trays; {@link #encode()} carries the trays only.
 *
 * <p>{@code turns}: the side nozzles the player turned in the constructor (finding F3, owner decision 2026-10-27), each
 * to the side it faces; every other side nozzle takes its side by itself ({@link ColumnStructure}). Empty for every column
 * built before the field existed, which is how those columns take their sides. Saved beside the trays, roles by name.
 */
public record ColumnTrays(int trays, int feed, List<Integer> draws, List<Integer> steam, List<Pumparound> pumparounds,
                          @org.jetbrains.annotations.Nullable ColumnComponents components, List<Turn> turns) {
    /** Tray spacing (the solver's {@code V3TrayHydraulics} spacing) and the disengagement space above the top tray, m. */
    public static final double SPACING = .6, TOP_SPACE = 1.2;
    public static final int MAX_TRAYS = 64, MAX_DRAWS = 4, MAX_STEAM = 2, MAX_PUMPAROUNDS = 4;
    /** The roles of a column's side nozzles: the ones a player may turn. */
    public static final java.util.Set<EquipmentShape.Role> SIDE_ROLES = java.util.EnumSet.of(EquipmentShape.Role.REFLUX, EquipmentShape.Role.FEED,
            EquipmentShape.Role.PUMPAROUND_DRAW, EquipmentShape.Role.RETURN, EquipmentShape.Role.VAPOUR_RETURN, EquipmentShape.Role.DRAW, EquipmentShape.Role.STEAM,
            EquipmentShape.Role.BOTTOMS);
    /** The most turns a column keeps: one per side nozzle a design can have. */
    public static final int MAX_TURNS = 3 + 2 * MAX_PUMPAROUNDS + MAX_DRAWS + MAX_STEAM + 1;

    /**
     * A side nozzle turned by the player: its role, which one of its role it is (from 1, in the trays' order: the first
     * pumparound's draw is 1), and the side it faces, an index into {@link EquipmentShape#side}: the front, the left, the
     * back or the right, as a drum's side connection names them. A turn for a nozzle the trays do not have changes nothing.
     */
    public record Turn(EquipmentShape.Role role, int index, int side) {
        public Turn {
            Objects.requireNonNull(role, "role");
            if (!SIDE_ROLES.contains(role)) throw new IllegalArgumentException("Not a side nozzle: " + role);
            if (index < 1 || index > MAX_DRAWS + MAX_PUMPAROUNDS) throw new IllegalArgumentException("A nozzle's number is 1 or more, not " + index);
            if (side < 0 || side >= EquipmentShape.CONNECTION_SIDES) throw new IllegalArgumentException("A nozzle faces side 0 to 3, not " + side);
        }
    }

    /** A pumparound draws liquid from {@code draw} and returns it, cooled, to {@code ret} above it. */
    public record Pumparound(int draw, int ret) {
    }

    public ColumnTrays {
        draws = List.copyOf(draws);
        steam = List.copyOf(steam);
        pumparounds = List.copyOf(pumparounds);
        turns = List.copyOf(turns);
        if (trays < 1 || trays > MAX_TRAYS) throw new IllegalArgumentException("A column has 1 to " + MAX_TRAYS + " trays, not " + trays);
        if (feed < 1 || feed > trays + 1) throw new IllegalArgumentException("The feed enters on tray 1 to " + (trays + 1) + ", not " + feed);
        if (draws.size() > MAX_DRAWS || steam.size() > MAX_STEAM || pumparounds.size() > MAX_PUMPAROUNDS)
            throw new IllegalArgumentException("Too many side draws, steam feeds or pumparounds");
        for (int draw : draws) if (draw < 1 || draw > trays) throw new IllegalArgumentException("Side draw outside the trays: " + draw);
        for (int stage : steam) if (stage < 1 || stage > trays + 1) throw new IllegalArgumentException("Steam stage outside the column: " + stage);
        for (var p : pumparounds)
            if (p.ret < 1 || p.ret > p.draw || p.draw > trays) throw new IllegalArgumentException("Pumparound must return at or above its draw tray: " + p);
        if (components != null && components.pumparoundPumps().size() != pumparounds.size())
            throw new IllegalArgumentException("One pumparound pump per pumparound: " + components.pumparoundPumps().size() + " pumps, " + pumparounds.size() + " pumparounds");
        if (turns.size() > MAX_TURNS) throw new IllegalArgumentException("Too many turned nozzles: " + turns.size());
        var seen = new java.util.HashSet<String>();
        for (var turn : turns) if (!seen.add(turn.role() + "#" + turn.index())) throw new IllegalArgumentException("A nozzle turned twice: " + turn);
    }

    /** Trays with a component design and no turned nozzle. */
    public ColumnTrays(int trays, int feed, List<Integer> draws, List<Integer> steam, List<Pumparound> pumparounds,
                       @org.jetbrains.annotations.Nullable ColumnComponents components) {
        this(trays, feed, draws, steam, pumparounds, components, List.of());
    }

    /** Trays without a component design. */
    public ColumnTrays(int trays, int feed, List<Integer> draws, List<Integer> steam, List<Pumparound> pumparounds) {
        this(trays, feed, draws, steam, pumparounds, null, List.of());
    }

    /** These trays with the component design {@code next} (null: none). */
    public ColumnTrays withComponents(@org.jetbrains.annotations.Nullable ColumnComponents next) {
        return new ColumnTrays(trays, feed, draws, steam, pumparounds, next, turns);
    }

    /** These trays with the side nozzles turned as {@code next}. */
    public ColumnTrays withTurns(List<Turn> next) {
        return new ColumnTrays(trays, feed, draws, steam, pumparounds, components, next);
    }

    /** Turns for a wire packet: role ordinal, number and side per turn (a wire format: no compatibility, AGENTS.md). */
    public static int[] encodeTurns(List<Turn> turns) {
        int[] out = new int[3 * turns.size()];
        for (int i = 0; i < turns.size(); i++) {
            out[3 * i] = turns.get(i).role().ordinal();
            out[3 * i + 1] = turns.get(i).index();
            out[3 * i + 2] = turns.get(i).side();
        }
        return out;
    }

    /** @throws IllegalArgumentException for a malformed array or an invalid turn */
    public static List<Turn> decodeTurns(int[] encoded) {
        if (encoded.length % 3 != 0 || encoded.length > 3 * MAX_TURNS) throw new IllegalArgumentException("Malformed turns: " + encoded.length + " values");
        var roles = EquipmentShape.Role.values();
        var turns = new java.util.ArrayList<Turn>();
        for (int i = 0; i < encoded.length; i += 3) {
            if (encoded[i] < 0 || encoded[i] >= roles.length) throw new IllegalArgumentException("Unknown role " + encoded[i]);
            turns.add(new Turn(roles[encoded[i]], encoded[i + 1], encoded[i + 2]));
        }
        return turns;
    }

    /** The side the player turned nozzle {@code index} of {@code role} to, or -1 where it takes its side by itself. */
    public int turn(EquipmentShape.Role role, int index) {
        for (var turn : turns) if (turn.role() == role && turn.index() == index) return turn.side();
        return -1;
    }

    /** The record's text, with the components and the turns only where there are some: a column without them reads as it did before the fields. */
    @Override
    public String toString() {
        return "ColumnTrays[trays=" + trays + ", feed=" + feed + ", draws=" + draws + ", steam=" + steam + ", pumparounds=" + pumparounds
                + (components == null ? "" : ", components=" + components) + (turns.isEmpty() ? "" : ", turns=" + turns) + "]";
    }

    /** A plain column: trays and the feed, nothing else. */
    public static ColumnTrays simple(int trays, int feed) {
        return new ColumnTrays(trays, feed, List.of(), List.of(), List.of());
    }

    /**
     * The sump's stack under the bottom tray (batch 2026-10-27-column-sump, {@code COLUMN_SUMP_SURVEY.md}), m: the low
     * liquid level over the bottom tangent; the span to the high level, about 7 min of bottoms at 0.2 m/min (a flux per
     * square metre, so the same at every diameter); the high level to the reboiler return's bottom; its top to the bottom
     * tray. The return ({@link ColumnStructure#returnOd}) stands between the last two.
     */
    static final double LOW_LEVEL = .3, LEVEL_SPAN = 1.5, UNDER_RETURN = .3, OVER_RETURN = .45;

    /**
     * Space under the bottom tray for the sump and the reboiler return, m: the sump's stack to the next whole metre (3 m
     * to D 1.5, 4 m to D 8), so the bottom tray stands on a block face and the trays over it keep their cell rows, as
     * with the 2 m sump before the stack; or half the diameter where that is more, as before (a wide column's head holds
     * minutes of bottoms by itself, so its sump keeps its height and its nozzles their rows).
     */
    public static double bottomSpace(double diameter) {
        double stack = LOW_LEVEL + LEVEL_SPAN + UNDER_RETURN + ColumnStructure.returnOd(diameter) + OVER_RETURN;
        return Math.max(diameter / 2, Math.ceil(stack - 1e-9));
    }

    /** Tangent-to-tangent length, whole metres: the trays, the space above them and the sump. */
    public int tangentLength(double diameter) {
        return (int) Math.ceil(TOP_SPACE + (trays - 1) * SPACING + bottomSpace(diameter) - 1e-9);
    }

    /** Height of tray {@code k} (1 at the top) above the bottom tangent line, m; stage {@code trays + 1} is the sump. */
    public double trayHeight(double diameter, int k) {
        return Objects.checkIndex(k - 1, trays + 1) == trays ? bottomSpace(diameter) / 2 : bottomSpace(diameter) + (trays - k) * SPACING;
    }

    /**
     * The trays of a column calculator's input: its interior trays (at least one), feed stage, side draws, steam feeds
     * and pumparounds, by the calculator's own numbers.
     */
    public static ColumnTrays of(com.wormzjl.icheme.science.column.v3.V3ColumnInput input) {
        var draws = new ArrayList<Integer>();
        for (var draw : input.sideDraws()) draws.add(draw.trayNumber());
        var steam = new ArrayList<Integer>();
        for (var feed : input.steamFeeds()) steam.add(feed.stageNumber());
        var pumparounds = new ArrayList<Pumparound>();
        for (var p : input.pumparounds()) pumparounds.add(new Pumparound(p.drawTray(), p.returnTray()));
        return new ColumnTrays(Math.max(1, input.stageCount()), input.feedStageNumber(), List.of(), List.of(), List.of())
                .refit(Math.max(1, input.stageCount()), draws, steam, pumparounds);
    }

    /**
     * The same layout over {@code trays} trays: the feed kept within the column, draws, steam and pumparounds that no longer
     * fit dropped; no component design (an edited layout is no longer the one the calculator saved).
     */
    public ColumnTrays withTrays(int trays) {
        return refit(trays, draws, steam, pumparounds);
    }

    /** The same layout with the feed on {@code feed}; no component design. */
    public ColumnTrays withFeed(int feed) {
        return new ColumnTrays(trays, feed, draws, steam, pumparounds);
    }

    private ColumnTrays refit(int count, List<Integer> draws, List<Integer> steam, List<Pumparound> pumparounds) {
        int n = Math.max(1, Math.min(MAX_TRAYS, count));
        return new ColumnTrays(n, Math.max(1, Math.min(n + 1, feed)), draws.stream().filter(d -> d <= n).limit(MAX_DRAWS).toList(),
                steam.stream().filter(s -> s <= n + 1).limit(MAX_STEAM).toList(),
                pumparounds.stream().filter(p -> p.draw <= n && p.ret >= 1 && p.ret <= p.draw).limit(MAX_PUMPAROUNDS).toList());
    }

    /** Trays, feed, then each list with its count first: draws, steam stages, pumparound (draw, return) pairs. */
    public int[] encode() {
        var out = new ArrayList<Integer>(List.of(trays, feed, draws.size()));
        out.addAll(draws);
        out.add(steam.size());
        out.addAll(steam);
        out.add(pumparounds.size());
        for (var p : pumparounds) {
            out.add(p.draw());
            out.add(p.ret());
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The longest {@link #encode()} array. */
    public static final int MAX_ENCODED = 5 + MAX_DRAWS + MAX_STEAM + 2 * MAX_PUMPAROUNDS;

    /** @throws IllegalArgumentException for a truncated, overlong or invalid layout */
    public static ColumnTrays decode(int[] data) {
        try {
            int i = 0, trays = data[i++], feed = data[i++];
            var draws = new ArrayList<Integer>();
            for (int n = data[i++]; n > 0; n--) draws.add(data[i++]);
            var steam = new ArrayList<Integer>();
            for (int n = data[i++]; n > 0; n--) steam.add(data[i++]);
            var pumparounds = new ArrayList<Pumparound>();
            for (int n = data[i++]; n > 0; n--) pumparounds.add(new Pumparound(data[i++], data[i++]));
            if (i != data.length) throw new IllegalArgumentException("Trailing tray data");
            return new ColumnTrays(trays, feed, draws, steam, pumparounds);
        } catch (ArrayIndexOutOfBoundsException truncated) {
            throw new IllegalArgumentException("Truncated tray data");
        }
    }
}
