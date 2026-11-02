package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A column design saved by a column calculator (batch 2026-10-26-multi-machine-framework, plan section 4.3): the trays
 * with their components and the diameter an equipment constructor builds the column from. Saving checks the structure
 * only; the run checks the thermodynamics.
 *
 * @param trays    the tray layout, by the calculator's numbers, with its component design
 * @param diameter the column's diameter, m: the calculator's to the nearest half metre
 */
public record ColumnDesign(ColumnTrays trays, double diameter) {
    public ColumnDesign {
        Objects.requireNonNull(trays, "trays");
        if (trays.components() == null) throw new IllegalArgumentException("A saved column design has its components");
        if (!(diameter >= EquipmentShape.MIN_DIAMETER && diameter <= EquipmentShape.MAX_COLUMN_DIAMETER) || diameter * 2 != Math.rint(diameter * 2))
            throw new IllegalArgumentException("A column's diameter is " + EquipmentShape.MIN_DIAMETER + " to " + EquipmentShape.MAX_COLUMN_DIAMETER + " m in half metres, not " + diameter);
    }

    /** The component design, its flags resolved from the design's numbers when it was saved. */
    public ColumnComponents components() {
        return trays.components();
    }

    /** The diameter a column is built at from a calculator's sized diameter: to the nearest half metre, within the column sizes. */
    public static double builtDiameter(double sized) {
        return Math.max(EquipmentShape.MIN_DIAMETER, Math.min(EquipmentShape.MAX_COLUMN_DIAMETER, Math.round(sized * 2) / 2.0));
    }

    /**
     * The design of a calculator input with the player's component choices, after the structure checks: the column has a
     * diameter and at least one tray, its draws, steam feeds and pumparounds lie on its trays, every nozzle finds a row by
     * its tray on a side of the shell (on the least skirt; the constructor checks its own again, {@link #misfit}), and a column
     * without reboiler duty is stripped by steam into its sump (the column solver refuses it otherwise: nothing boils up
     * at the bottom).
     *
     * @throws IllegalArgumentException with the reason, in words for the player
     */
    public static ColumnDesign of(V3ColumnInput input, ColumnComponents.Choices choices) {
        Objects.requireNonNull(choices, "choices");
        if (!(input.columnDiameterMetres() > 0)) throw new IllegalArgumentException("set the column diameter; the column is built at it");
        if (input.stageCount() < 1) throw new IllegalArgumentException("a column needs a tray between the condenser and the reboiler");
        var trays = ColumnTrays.of(input);
        if (trays.trays() != input.stageCount() || trays.draws().size() != input.sideDraws().size() || trays.steam().size() != input.steamFeeds().size()
                || trays.pumparounds().size() != input.pumparounds().size())
            throw new IllegalArgumentException("a side draw, steam feed or pumparound lies outside the trays");
        double duty = input.specifications().stream().filter(V3ColumnSpecification.ReboilerDuty.class::isInstance)
                .mapToDouble(s -> ((V3ColumnSpecification.ReboilerDuty) s).watts()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the design has no reboiler duty"));
        if (!ColumnComponents.kettleRequired(duty) && !V3SteamFeedSpec.hasSumpFeed(input))
            throw new IllegalArgumentException("without reboiler duty the column needs stripping steam into its bottom tray");
        double diameter = builtDiameter(input.columnDiameterMetres());
        var design = new ColumnDesign(trays.withComponents(ColumnComponents.resolve(choices, input.topPressurePascal(), duty, trays.pumparounds().size()))
                .withOperating(ColumnOperatingPoint.of(input)), diameter);
        misfit(EquipmentShape.column(diameter, design.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(diameter)))
                .ifPresent(reason -> {
                    throw new IllegalArgumentException(reason);
                });
        return design;
    }

    /**
     * Why a column's shape cannot carry its design: the first nozzle with no free row by its tray, in words for the player
     * with the tray count the calculator shows (its trays and the condenser and reboiler); empty when every nozzle fits.
     */
    public static Optional<String> misfit(EquipmentShape column) {
        var missing = ColumnStructure.unplaced(column);
        if (missing.isEmpty()) return Optional.empty();
        var u = missing.getFirst();
        String where = u.turned() ? "by its tray on the " + SIDE_NAMES[u.side()] + " side it is turned to" : "by its tray on any side";
        return Optional.of(nozzle(u) + " has no free row " + where + " of a " + (column.trays().trays() + 2) + "-tray column");
    }

    /** The sides a column's side nozzle faces, by {@link EquipmentShape#side} index, in words. */
    private static final String[] SIDE_NAMES = {"front", "left", "back", "right"};

    /**
     * A column's side nozzle (finding F3): its role; which one of its role it is (from 1, in the trays' order, as a
     * {@link ColumnTrays.Turn} names it); its home side, the one it tries first by itself; the side it faces, or was to
     * face where the shell has no row for it (indices into {@link EquipmentShape#side}: the front, the left, the back, the
     * right); whether the player turned it there; and the nozzle, or null where the shell has no row and side for it.
     */
    public record SideNozzle(EquipmentShape.Role role, int index, int home, int side, boolean turned, @org.jetbrains.annotations.Nullable EquipmentShape.Nozzle nozzle) {
        public boolean placed() {
            return nozzle != null;
        }
    }

    /** Every side nozzle of a column's shape in placing order (the reflux, the feed, the pumparounds, the reboiler's return, the draws, the steam, the bottoms), placed or not. */
    public static List<SideNozzle> sides(EquipmentShape column) {
        return ColumnStructure.sides(column);
    }

    private static String nozzle(SideNozzle u) {
        return switch (u.role()) {
            case REFLUX -> "the reflux nozzle";
            case FEED -> "the feed nozzle";
            case PUMPAROUND_DRAW -> "pumparound " + u.index() + "'s draw";
            case RETURN -> "pumparound " + u.index() + "'s return";
            case VAPOUR_RETURN -> "the reboiler's vapour return";
            case DRAW -> "side draw " + u.index();
            case STEAM -> "steam feed " + u.index();
            case BOTTOMS -> "the bottoms nozzle";
            default -> u.role().name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
