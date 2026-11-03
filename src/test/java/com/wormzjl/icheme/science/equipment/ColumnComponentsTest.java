package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.OrganicRefluxRatio;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.ReboilerDuty;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.column.v3.V3TestInputs;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Choices;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Pump;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The column calculator's component design and its Save design checks (batch 2026-10-26-multi-machine-framework, plan
 * sections 4.2 and 4.3): which components the design's numbers require, and which designs the structure refuses.
 */
class ColumnComponentsTest {
    /** The bundled crude column: 40 trays, three draws with their pumparounds, steam into the sump, no reboiler duty, 250 kPa. */
    private static V3ColumnInput crude() {
        return V3TestInputs.methaneCduInput();
    }

    /** {@code input} with its top pressure, reboiler duty, reflux ratio, draws and steam feeds replaced. */
    private static V3ColumnInput with(V3ColumnInput in, double top, double duty, double ratio, List<V3SideDrawSpec> draws, List<V3SteamFeedSpec> steam) {
        var specs = new ArrayList<V3ColumnSpecification>();
        for (var s : in.specifications())
            specs.add(s instanceof ReboilerDuty ? new ReboilerDuty(duty) : s instanceof OrganicRefluxRatio ? new OrganicRefluxRatio(ratio) : s);
        return new V3ColumnInput(in.schemaVersion(), in.packageId(), in.assayId(), in.componentBasis(), in.feedComponentMolarFlowsMolPerSecond(),
                in.feedTemperatureKelvin(), in.stageCount(), in.feedStageNumber(), top, in.stagePressureDropPascal(), specs, draws, steam,
                in.pumparounds(), in.columnDiameterMetres());
    }

    private static V3ColumnInput with(double top, double duty, double ratio) {
        var in = crude();
        return with(in, top, duty, ratio, in.sideDraws(), in.steamFeeds());
    }

    @Test
    void anEjectorIsRequiredBelowAmbientPressureOnly() {
        assertEquals(101_325, PhaseLayout.AMBIENT_PRESSURE);
        assertTrue(ColumnComponents.ejectorRequired(5_000));
        assertTrue(ColumnComponents.ejectorRequired(101_324.9));
        assertFalse(ColumnComponents.ejectorRequired(101_325));
        assertFalse(ColumnComponents.ejectorRequired(250_000));
        assertTrue(ColumnDesign.of(with(5_000, 0, 1), Choices.DEFAULT).components().ejector(), "a vacuum design");
        assertFalse(ColumnDesign.of(crude(), Choices.DEFAULT).components().ejector(), "the crude column at 250 kPa");
    }

    @Test
    void aKettleIsRequiredWithReboilerDutyOnly() {
        assertFalse(ColumnDesign.of(crude(), Choices.DEFAULT).components().kettle(), "sump steam strips the crude column");
        assertTrue(ColumnDesign.of(with(250_000, 2.4e6, 1), Choices.DEFAULT).components().kettle());
    }

    /** Owner answer Q2: the reflux line is part of every design, also at a zero reflux ratio. */
    @Test
    void theRefluxNozzleIsBuiltAtEveryRefluxRatio() {
        for (double ratio : new double[]{0, .5, 3}) {
            var design = ColumnDesign.of(with(250_000, 0, ratio), Choices.DEFAULT);
            var shape = EquipmentShape.column(design.diameter(), design.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(design.diameter()));
            assertEquals(1, shape.nozzles().stream().filter(n -> n.role() == EquipmentShape.Role.REFLUX).count(), "reflux ratio " + ratio);
        }
    }

    @Test
    void aColumnWithoutReboilerDutyNeedsSumpSteam() {
        var in = crude();
        var traySteam = List.of(new V3SteamFeedSpec(in.stageCount(), 100, 533.15));
        var refused = assertThrows(IllegalArgumentException.class, () -> ColumnDesign.of(with(in, 250_000, 0, 1, in.sideDraws(), traySteam), Choices.DEFAULT));
        assertTrue(refused.getMessage().contains("stripping steam"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ColumnDesign.of(with(in, 250_000, 0, 1, in.sideDraws(), List.of()), Choices.DEFAULT));
        // Reboiler duty boils up without steam.
        assertTrue(ColumnDesign.of(with(in, 250_000, 2.4e6, 1, in.sideDraws(), List.of()), Choices.DEFAULT).components().kettle());
    }

    @Test
    void theChoicesFitTheDesignedPumparoundsAndSurviveTheirNames() {
        var design = ColumnDesign.of(crude(), new Choices(Condenser.EXCHANGER, Pump.IN_LINE, List.of(Pump.IN_LINE)));
        var c = design.components();
        assertEquals(Condenser.EXCHANGER, c.condenser());
        assertEquals(Pump.IN_LINE, c.refluxPump());
        assertEquals(List.of(Pump.IN_LINE, Pump.STANDARD, Pump.STANDARD), c.pumparoundPumps(), "one pump per pumparound, the rest standard");
        assertEquals(c, ColumnComponents.named("EXCHANGER", "IN_LINE", List.of("IN_LINE", "STANDARD", "STANDARD"), false, false));
        assertEquals(8, design.diameter());
        assertEquals(ColumnTrays.of(crude()).withComponents(c).withOperating(ColumnOperatingPoint.of(crude())), design.trays(), "the calculator's trays with their components and its operating point");
        var unknown = assertThrows(IllegalArgumentException.class, () -> ColumnComponents.named("AIR_COOLER", "STANDARD", List.of("JET"), false, false));
        assertTrue(unknown.getMessage().startsWith("pumparound pump 1"), unknown.getMessage());
        assertEquals(List.of(Pump.STANDARD, Pump.IN_LINE), Choices.DEFAULT.withPumparoundPump(1, Pump.IN_LINE).pumparoundPumps());
    }

    /** S3b: member counts fit the designed pumparounds (a further cooler one unit), stay in 1 to {@code MAX_UNITS}, and a design without them reads one unit a step. */
    @Test
    void memberCountsFitThePumparoundsAndStayInRange() {
        var units = ColumnComponents.Units.ONE.withCondenser(2).withKettle(3).withCooler(1, 4);
        assertEquals(new ColumnComponents.Units(2, 3, List.of(1, 4)), units);
        var design = ColumnDesign.of(crude(), new Choices(Condenser.AIR_COOLER, Pump.STANDARD, List.of(), units));
        assertEquals(new ColumnComponents.Units(2, 3, List.of(1, 4, 1)), design.components().units(), "one count per pumparound, a further cooler one unit");
        assertEquals(design.components().units(), design.components().choices().units(), "the Components page shows them again");
        assertEquals(ColumnComponents.Units.ONE.fit(3), new ColumnComponents(Condenser.EXCHANGER, Pump.STANDARD, List.of(Pump.STANDARD, Pump.STANDARD, Pump.STANDARD), false, false).units());
        assertThrows(IllegalArgumentException.class, () -> new ColumnComponents.Units(ColumnComponents.MAX_UNITS + 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ColumnComponents.Units(1, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ColumnComponents(Condenser.EXCHANGER, Pump.STANDARD, List.of(), false, false, new ColumnComponents.Units(1, 1, List.of(2))),
                "no cooler count without its pumparound");
    }

    /**
     * An air-then-water condenser (COOLER_MODEL_PLAN.md section 6): saved by name, its water trim's shells counted 1 to
     * {@code MAX_UNITS} and kept through a fit and the other counts' edits; a design saved before the trim reads one shell.
     */
    @Test
    void anAirThenWaterCondenserCountsItsTrimShells() {
        var units = ColumnComponents.Units.ONE.withTrim(3).withCondenser(2).withCooler(0, 2).withKettle(4);
        assertEquals(new ColumnComponents.Units(2, 4, List.of(2), 3), units);
        assertEquals(3, units.fit(3).trim(), "a fit keeps the trim");
        assertEquals(1, new ColumnComponents.Units(2, 1, List.of()).trim(), "the counts before the trim: one shell");
        assertThrows(IllegalArgumentException.class, () -> ColumnComponents.Units.ONE.withTrim(ColumnComponents.MAX_UNITS + 1));
        assertThrows(IllegalArgumentException.class, () -> ColumnComponents.Units.ONE.withTrim(0));
        var design = ColumnDesign.of(crude(), new Choices(Condenser.AIR_THEN_WATER, Pump.STANDARD, List.of(), units));
        assertEquals(Condenser.AIR_THEN_WATER, design.components().condenser());
        assertEquals(3, design.components().units().trim());
        assertEquals(Condenser.AIR_THEN_WATER, ColumnComponents.named("air_then_water", "standard", List.of(), false, false).condenser(), "saved by name");
    }

    /** Finding F1 through the save: a nozzle without a row by its tray on any side refuses the design and names itself (finding F3). */
    @Test
    void aDesignWhoseNozzleFindsNoRowIsNotSaved() {
        var in = crude();
        var short4 = new V3ColumnInput(in.schemaVersion(), in.packageId(), in.assayId(), in.componentBasis(), in.feedComponentMolarFlowsMolPerSecond(),
                in.feedTemperatureKelvin(), 4, 2, in.topPressurePascal(), in.stagePressureDropPascal(), in.specifications(),
                List.of(new V3SideDrawSpec(1, 1), new V3SideDrawSpec(2, 1), new V3SideDrawSpec(3, 1), new V3SideDrawSpec(4, 1)),
                List.of(new V3SteamFeedSpec(4, 10, 533.15), new V3SteamFeedSpec(5, 10, 533.15)), List.of(), 2.0);
        var refused = assertThrows(IllegalArgumentException.class, () -> ColumnDesign.of(short4, Choices.DEFAULT));
        assertTrue(refused.getMessage().matches("(the reboiler's vapour return|side draw \\d|steam feed \\d) has no free row by its tray on any side of a 6-tray column"),
                refused.getMessage());
        var prescribed = new V3ColumnInput(in.schemaVersion(), in.packageId(), in.assayId(), in.componentBasis(), in.feedComponentMolarFlowsMolPerSecond(),
                in.feedTemperatureKelvin(), in.stageCount(), in.feedStageNumber(), in.topPressurePascal(), in.stagePressureDropPascal(), in.specifications(),
                in.sideDraws(), in.steamFeeds(), in.pumparounds(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
        assertThrows(IllegalArgumentException.class, () -> ColumnDesign.of(prescribed, Choices.DEFAULT), "no diameter, no column");
    }
}
