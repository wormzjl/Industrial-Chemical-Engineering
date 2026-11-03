package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnInspection;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.column.v3.V3TrayHydraulics;
import com.wormzjl.icheme.science.column.v3.V3TrayHydraulicsSummary;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A column (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.1, 3.4 and 3.5): its diameter in the world's half
 * metres, each rated by the real solver on the run's own column input at that diameter. The run's solve stands for the door's
 * diameter, so only another diameter takes a trial. A trial that does not solve is no fit, red on {@link Limit#COLUMN_SOLVES}.
 *
 * <p>Auto starts where the run's flood says: at a fixed traffic flood goes as one over the area, so the door's diameter times the
 * square root of its design flood over the Should's gives the first trial. From there it steps down while a size meets every limit
 * and up while none does. Every row falls as the column widens (flood, downcomer speed, tray drop, height over diameter) or rises
 * (sump hold), so the answer is the one a walk from the smallest would take, in two or three trials instead of thirty.
 */
public final class ColumnSizing {
    /** The step between the diameters the constructor builds, m. */
    private static final double STEP = .5;

    private ColumnSizing() {
    }

    /**
     * A size of column.
     *
     * @param diameter m, in half metres
     * @param layout   its trays and components, as the door's column builds them at any diameter
     */
    public record Size(double diameter, ColumnTrays layout) implements UnitSize {
        /** Throws for a diameter the constructor does not build, or a shell whose nozzles find no row. */
        @Override public EquipmentShape shape() {
            var design = new ColumnDesign(layout, diameter);
            var shape = EquipmentShape.column(design.diameter(), design.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(diameter));
            ColumnDesign.misfit(shape).ifPresent(reason -> {
                throw new IllegalArgumentException(reason);
            });
            return shape;
        }

        /** The height over grade of stage {@code k}'s liquid (1 the top tray, the tray count plus 1 the sump), m: the skirt and the tray's place on the shell. */
        public double level(int k) {
            return EquipmentShape.minimumSkirt(diameter) + layout.trayHeight(diameter, k);
        }

        /** Tangent to tangent, m. */
        public double height() {
            return layout.tangentLength(diameter);
        }
    }

    /**
     * A column as the run left it.
     *
     * @param solved     the column input its last solve took, the feed V3 saw included
     * @param layout     its trays and components
     * @param diameter   the door's, m
     * @param hydraulics the run's tray hydraulics, at the door's diameter
     * @param liquid     the most liquid on any tray at design flow, m³/s: the downcomer's
     * @param bottoms    the bottoms at design flow, m³/s: the sump's
     * @param design     the design flow over the steady
     */
    public record Load(V3ColumnInput solved, ColumnTrays layout, double diameter, V3TrayHydraulicsSummary hydraulics, double liquid, double bottoms, double design) {
        /**
         * {@code door}'s column as its run's {@code display} shows it; null if the shown run has no profile or hydraulics, or its
         * trays cannot be laid out.
         */
        public static @Nullable Load of(ColumnDoor door, V3ColumnDisplayResult display, FlowStream bottoms, DesignBasis basis, FlowThermo thermo) {
            var inspection = display.inspection().orElse(null);
            var hydraulics = display.trayHydraulics().orElse(null);
            if (inspection == null || hydraulics == null) return null;
            var solved = inspection.input();
            ColumnTrays layout;
            try {
                // The shell carries the door's steam rows: water the feed brings joins V3's steam on the feed tray, through the feed nozzle.
                var steam = door.steam().stream().map(s -> new V3SteamFeedSpec(s.stage(), s.flow(), s.temperature())).toList();
                layout = ColumnDesign.layout(at(solved, steam, solved.columnDiameterMetres()), new ColumnComponents.Choices(door.condenser(), ColumnComponents.Pump.STANDARD, List.of()).fit(door.pumparounds().size()));
            } catch (IllegalArgumentException refused) {
                return null;
            }
            double liquid = 0;
            var nodes = inspection.nodes();
            for (int k = 1; k <= solved.stageCount(); k++) {
                var node = nodes.get(k);
                if (node.liquidMolPerSecond() > 0) liquid = Math.max(liquid, ColumnSizing.liquid(thermo, solved, node).state().volume());
            }
            return new Load(solved, layout, door.diameter(), hydraulics, liquid * basis.designFlow(), bottoms.empty() ? 0 : bottoms.state().volume() * basis.designFlow(), basis.designFlow());
        }

        /** Its size at {@code diameter}. */
        public Size size(double diameter) {
            return new Size(diameter, layout);
        }
    }

    /** {@code node}'s liquid on the sheet's axis, at the node's own temperature and pressure, as the column's draws leave. */
    static FlowStream liquid(FlowThermo thermo, V3ColumnInput solved, V3ColumnInspection.Node node) {
        return stream(thermo, solved, node.liquidFractions(), node.liquidMolPerSecond(), 0, node.temperatureKelvin(), node.pressurePascal());
    }

    /** {@code flow} mol/s of {@code fractions} on the column's basis, plus {@code water} mol/s, on the sheet's axis. */
    static FlowStream stream(FlowThermo thermo, V3ColumnInput solved, List<Double> fractions, double flow, double water, double temperature, double pressure) {
        var axis = thermo.axis();
        var ids = solved.componentBasis().componentIds();
        double[] amounts = new double[axis.size()];
        for (int i = 0; i < ids.size(); i++) {
            int at = axis.indexOf(ids.get(i));
            if (at >= 0) amounts[at] += fractions.get(i) * flow;
        }
        amounts[axis.size() - 1] += water;
        return thermo.atTemperature(temperature, pressure, amounts, SolidInventory.EMPTY);
    }

    /** {@code input} at {@code diameter}, with {@code steam}. */
    private static V3ColumnInput at(V3ColumnInput input, List<V3SteamFeedSpec> steam, double diameter) {
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(), input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(),
                input.stageCount(), input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(), input.specifications(), input.sideDraws(), steam, input.pumparounds(), diameter);
    }

    /**
     * The real column at each diameter a sizing asks for, once each: the door's from the run, any other by a solve of the run's own
     * input, warm from the last trial that solved. It runs on the caller's thread, and {@code checkpoint} reaches every solve.
     */
    public static final class Trials {
        private final Load load;
        private final MaterialCatalog catalog;
        private final Runnable checkpoint;
        private final Map<Double, Optional<V3TrayHydraulicsSummary>> done = new HashMap<>();
        private @Nullable V3NeuralSeed seed;
        private int solves;

        public Trials(Load load, MaterialCatalog catalog, Runnable checkpoint) {
            this.load = load;
            this.catalog = catalog;
            this.checkpoint = checkpoint;
            done.put(load.diameter(), Optional.of(load.hydraulics()));
        }

        /** The tray hydraulics at {@code diameter}, null if the column does not solve there. */
        public @Nullable V3TrayHydraulicsSummary at(double diameter) {
            var known = done.get(diameter);
            if (known != null) return known.orElse(null);
            solves++;
            var outcome = ColumnUnit.solve(ColumnSizing.at(load.solved(), load.solved().steamFeeds(), diameter), seed, catalog, checkpoint);
            V3TrayHydraulicsSummary hydraulics = null;
            if (outcome instanceof V3ColumnOutcome.Success success) {
                seed = success.result().warmSeed().orElse(seed);
                hydraulics = success.result().trayHydraulics().orElse(null);
            }
            done.put(diameter, Optional.ofNullable(hydraulics));
            return hydraulics;
        }

        /** How many solves the trials took. */
        public int solves() {
            return solves;
        }
    }

    /** Every limit of {@code load} read at {@code size}: the door's diameter on the run's own solve, another on its trial. */
    public static Rating rate(Load load, Size size, Trials trials) {
        var readings = new ArrayList<Reading>();
        var shape = UnitSize.built(size);
        readings.add(UnitSize.reading(shape));
        double d = size.diameter(), area = Math.PI * d * d / 4;
        readings.add(Limit.COLUMN_DIAMETER.read(d));
        readings.add(Limit.COLUMN_TRAYS.read(load.solved().stageCount()));
        if (shape == null) return new Rating(readings);
        var hydraulics = trials.at(d);
        readings.add(Limit.COLUMN_SOLVES.read(hydraulics == null ? 0 : 1));
        if (hydraulics != null) {
            double flood = 100 * hydraulics.maximumFloodFraction() * load.design();
            readings.add(Limit.COLUMN_FLOOD_WORLD.read(flood));
            readings.add(Limit.COLUMN_FLOOD.read(flood));
            // The dry tray loses the square of its vapour's speed; the liquid's head on it stands as solved.
            readings.add(Limit.COLUMN_TRAY_DROP.read((hydraulics.worstTrayDryPascal() * load.design() * load.design() + hydraulics.worstTrayLiquidPascal()) / 1e3));
        }
        readings.add(Limit.COLUMN_DOWNCOMER.read(load.liquid() / (V3TrayHydraulics.DOWNCOMER_AREA_FRACTION * area)));
        if (load.bottoms() > 0) readings.add(Limit.COLUMN_SUMP.read(area * ColumnTrays.bottomSpace(d) / 2 / load.bottoms() / 60));
        readings.add(Limit.COLUMN_SLENDERNESS.read(size.height() / d));
        return new Rating(readings);
    }

    /** The least diameter that meets every limit; where none does, as {@link Selection#smallest} answers among those tried. */
    public static Selection.Choice<Size> auto(Load load, Trials trials) {
        var rated = new HashMap<Double, Rating>();
        java.util.function.DoubleFunction<Rating> rating = d -> rated.computeIfAbsent(d, at -> rate(load, load.size(at), trials));
        double low = EquipmentShape.MIN_DIAMETER, high = EquipmentShape.MAX_COLUMN_DIAMETER;
        double flood = 100 * load.hydraulics().maximumFloodFraction() * load.design();
        double d = Math.max(low, Math.min(high, Math.ceil(load.diameter() * Math.sqrt(flood / Limit.COLUMN_FLOOD.value()) / STEP - 1e-9) * STEP));
        if (rating.apply(d).settled()) while (d - STEP >= low - 1e-9 && rating.apply(d - STEP).settled()) d -= STEP;
        else while (d + STEP <= high + 1e-9 && !rating.apply(d += STEP).settled()) {
        }
        var tried = rated.keySet().stream().sorted().map(load::size).toList();
        return Selection.smallest(tried, size -> rated.get(size.diameter()));
    }
}
