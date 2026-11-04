package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.PressurePath;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S4a's gate on a real steady run of the sample (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.1, 3.6 and
 * 3.11), a slow gate: the pressure paths against the probe's stair in {@code sizing-sample.tsv}, Size all's one re-run on the sized
 * drops as section 3.11 gives it, nothing sized twice, and a rating with no trial column.
 */
class SizeAllSampleTest {
    /** The probe's stair stands in its printed hundredths. */
    private static final double HUNDREDTH = .005;

    private static Flowsheet sheet;
    private static FlowsheetRun run;
    private static FlowThermo thermo;
    private static SheetSizing.Sized proposal;
    private static SheetSizing.SizeAll all;

    @BeforeAll
    static void sizeTheSample() {
        sheet = SheetFixture.sample();
        run = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(sheet, SheetFixture.CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, run.status());
        thermo = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
        });
        MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
            proposal = SheetSizing.pass(sheet, run, thermo, SheetSizing.Entries.DEFAULT);
            all = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.sizeAll(sheet, run, thermo);
            return null;
        });
        for (var path : proposal.paths()) {
            System.out.printf(Locale.ROOT, "PATH\t%s -> %s\tfrom %.2f head %.2f to %.2f %s\tavailable %.2f lost %.2f spare %.2f%s%n", path.start(), path.end(), path.from(), path.head(), path.to(),
                    path.set() ? "set" : "delivered", path.available(), path.lost(), path.spare(), path.taker() == null ? "" : "\ttaker " + path.taker());
            for (var step : path.steps()) System.out.printf(Locale.ROOT, "STEP\t%s\t%s\t%.3f\t%.3f%n", step.item(), step.passage(), step.lost(), step.typed());
        }
        var before = run.streams();
        var after = all.run().streams();
        System.out.printf(Locale.ROOT, "RUN\tS-6 %.3f -> %.3f bar\tS-7 %.2f -> %.2f C\tH-101 %.3f -> %.3f MW\tvapour %.4f -> %.4f\tstatus %s passes %d%n", before.get("S-6").pressure() / 1e5,
                after.get("S-6").pressure() / 1e5, before.get("S-7").temperature() - 273.15, after.get("S-7").temperature() - 273.15, run.reports().get("H-101").readings().get("duty") / 1e6,
                all.run().reports().get("H-101").readings().get("duty") / 1e6, run.reports().get("H-101").readings().get("vapour"), all.run().reports().get("H-101").readings().get("vapour"),
                all.run().status(), all.run().loops().isEmpty() ? 0 : all.run().loops().get(0).passes());
        all.run().reports().forEach((id, report) -> report.readings().forEach((key, value) -> {
            if (key.endsWith("drop") || key.equals("coils")) System.out.printf(Locale.ROOT, "SIZED\t%s\t%s\t%.4f bar%n", id, key, value / 1e5);
        }));
    }

    private static PressurePath path(SheetSizing.Sized sized, String start) {
        return sized.paths().stream().filter(path -> path.start().equals(start)).findFirst().orElseThrow();
    }

    /**
     * P-101's path: the probe's stair, step by step, to V-101, which holds its outlet and takes the rest. The probe ended the path at
     * V-101's inlet on the doors' drops, 9.00 bar, so V-101's own 2.28 counted as spent; here V-101 is the path's end at its set
     * 2.80 bar (2.72 before S4c), the spare is what it takes, and a run on sized drops leaves it the same. Two steps differ from the probe's, both by
     * the physics: H-101's radiant coil starts where its convection coil ends, 0.46 bar lower, so more of the crude flashes in it
     * (0.11 bar more than the probe's march, which restarted the radiant coil at the inlet's pressure), and the built cabin's coils
     * hold 87.6 MW, not the design's 86.2, on the same 11 passes (0.05 bar more); and the transfer line S-6, which the probe left
     * out because it ended at H-101's outlet, is a step of the path. 3.66 + 0.16 + 0.03 = 3.85 bar on S4a's least sizes.
     *
     * <p>S4c's spare moves four steps, each by its unit's size. PA 1 and PA 2 coolers take 4 m tubes, not 3: longer, 0.36 and 0.40
     * bar, not 0.30 and 0.33. E-101 takes a 1.25 m shell with 5 m tubes, not 1.00 × 6 m: more tubes, shorter, 0.17, not 0.44. H-101's
     * cabin of 12.0 × 45 m holds 105.4 MW on 13 passes of 8 MW, not 87.6 on 11: each pass carries 11/13 of the crude on a pass 2 %
     * longer, 1.48 bar, not 2.11. The path loses 3.07 bar.
     */
    @Test
    void p101sPathIsTheProbesStair() {
        var path = path(proposal, "P-101");
        String[] items = {"S-1", "T-101/pa 1 cooler", "S-2", "T-101/pa 2 cooler", "S-3", "T-101/pa 3 cooler", "S-4", "E-101", "S-5", "H-101", "S-6"};
        double[] lost = {.12, .36, .10, .40, .04, .29, .04, .17, .04, 1.48, .03};
        assertEquals(List.of(items), path.steps().stream().map(PressurePath.Step::item).toList());
        for (int i = 0; i < lost.length; i++) assertEquals(lost[i], path.steps().get(i).lost(), HUNDREDTH, items[i]);
        assertEquals(3.07, path.lost(), HUNDREDTH);
        assertEquals(14.00, path.from(), HUNDREDTH);
        assertTrue(path.set());
        assertEquals("V-101", path.taker());
        assertEquals(2.80, path.to(), HUNDREDTH);
        assertEquals(path.from() - path.to() - path.lost(), path.spare(), 1e-12);
    }

    /**
     * The residue's path: the probe's 0.99 bar, the sump's head with the doors' drops, to a product nothing holds. It loses 0.48,
     * not the probe's 0.47: S2's heat curves give E-102 724 m² where the probe's one heat capacity gave 741, which picks another
     * bank, 0.07 bar where the probe's lost 0.05 (plan section 4). S4c's spare takes larger units on it: E-101's 1.25 m shell, whose
     * residue side loses 0.06, not 0.15, and E-102's 6 × 3 bays of 2.5 × 6 m, 0.04, not 0.07. The path loses 0.37.
     */
    @Test
    void theResiduesPathIsTheProbes() {
        var residue = proposal.paths().stream().filter(p -> p.start().equals("T-101") && p.end().equals("RESIDUE")).findFirst().orElseThrow();
        assertEquals(List.of("S-9", "E-101", "S-10", "E-102", "RESIDUE"), residue.steps().stream().map(PressurePath.Step::item).toList());
        double[] lost = {.005, .06, .09, .045, .17};
        for (int i = 0; i < lost.length; i++) assertEquals(lost[i], residue.steps().get(i).lost(), HUNDREDTH, residue.steps().get(i).item());
        assertEquals(.49, residue.head(), HUNDREDTH);
        assertEquals(2.22, residue.to(), HUNDREDTH);
        assertEquals(.99, residue.available(), HUNDREDTH);
        assertEquals(.37, residue.lost(), HUNDREDTH);
        assertFalse(residue.set());
        assertSame(com.wormzjl.icheme.science.flowsheet.sizing.Rating.NONE, residue.rating());
    }

    /**
     * Section 3.11: the sized coils, coolers and exchanger lose about 2.8 bar at the run's flow where the doors typed 9.00, so S-6
     * rises from 5.00 to about 11.2 bar, less crude flashes at 365 °C, the furnace fires less, the feed reaches the column cooler,
     * and the sheet still closes. The run's sized drops lose 2.69 bar, not the plan's 2.8, which scaled the design stair by the
     * square of the flow: at 11.3 bar the coil's crude flashes less than at the design's 5, so its law loses less than the square
     * law says, and S-6 stands 0.11 bar above the plan's. The tolerances are the plan's "about": its last printed digit, and S-6's
     * 0.11 within 0.15.
     *
     * <p>S4c: with the 20 % spare the sized units are larger and lose less. The coils' 13 passes lose 1.07 bar at the run's flow,
     * and the sized steps 2.09 bar in all, so S-6 stands at 11.9 bar, 0.7 above S4a's least sizes. At 11.9 bar still less crude
     * flashes, a vapour share of 0.52, not 0.54; the furnace fires 73.9 MW, not 74.25, and the feed reaches the column at 349.8 °C, not
     * 350.5. The tolerances stay.
     *
     * <p>S4c also raises V-101's set outlet from 2.72 to 2.80 bar ("always have enough excess"): the feed flashes less across the
     * valve and reaches the column 0.24 K warmer, 359.70 °C before sizing and 350.02 after. Upstream of the valve nothing moves.
     */
    @Test
    void theSampleSizedMovesAsThePlanSays() {
        var before = run.streams();
        var after = all.run().streams();
        assertEquals(FlowsheetRun.Status.STEADY, all.run().status());
        assertEquals(5.00, before.get("S-6").pressure() / 1e5, HUNDREDTH);
        assertEquals(11.9, after.get("S-6").pressure() / 1e5, .15);
        assertEquals(78.36, run.reports().get("H-101").readings().get("duty") / 1e6, HUNDREDTH);
        assertEquals(73.93, all.run().reports().get("H-101").readings().get("duty") / 1e6, .1);
        assertEquals(359.7, before.get("S-7").temperature() - 273.15, .1);
        assertEquals(350.0, after.get("S-7").temperature() - 273.15, .15);
        assertEquals(.71, run.reports().get("H-101").readings().get("vapour"), HUNDREDTH);
        assertEquals(.52, all.run().reports().get("H-101").readings().get("vapour"), HUNDREDTH);
    }

    /** Nothing is sized twice: every Auto item holds the size Size all proposed, and that is the size its rating reads. */
    @Test
    void sizeAllAppliesTheProposalAndRatesIt() {
        var facts = all.sheet().sizing();
        proposal.units().forEach((id, unit) -> {
            if (unit.size() == null || unit.size() instanceof ColumnSizing.Size || unit.size() instanceof com.wormzjl.icheme.science.flowsheet.sizing.ValveSizing.Size) return;
            assertEquals(unit.size(), facts.unit(id).size(), id);
            assertEquals(SizingFacts.Mode.AUTO, facts.unit(id).mode(), id);
            assertEquals(unit.size(), all.sized().units().get(id).size(), id);
        });
        proposal.lines().forEach((id, line) -> {
            assertEquals(line.size(), facts.line(id).size(), id);
            assertEquals(line.size(), all.sized().lines().get(id).size(), id);
        });
        assertEquals(SizingFacts.NONE, sheet.sizing(), "the sheet it was given stays as it was");
    }

    /**
     * S3b: Auto's member counts reach the door, as the column's diameter does. The condenser's five shells are the design's count,
     * which the assembly asks for and builds (at most {@code ColumnComponents.MAX_UNITS}); each cooler keeps one shell.
     */
    @Test
    void theCondensersFiveShellsReachTheDoor() {
        var door = com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(all.sheet().units().stream().filter(u -> u.id().equals("T-101")).findFirst().orElseThrow().params());
        assertEquals(new com.wormzjl.icheme.science.equipment.ColumnComponents.Units(5, 1, List.of(1, 1, 1)), door.units());
        var choices = door.choices(com.wormzjl.icheme.science.equipment.ColumnComponents.Pump.STANDARD, List.of());
        assertEquals(5, choices.units().condenser(), "a design the calculator and the assembly take");
        assertEquals(1, com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(sheet.units().stream().filter(u -> u.id().equals("T-101")).findFirst().orElseThrow().params()).units().condenser(),
                "the sheet it was given keeps its door");
    }

    /**
     * S5: Send to block stores the column at its door's diameter with its member counts, the condenser's five shells as its count, and every
     * unit with a shape but the valve, each reading back whole as the block keeps it.
     */
    @Test
    void sendToBlockStoresTheColumnAtItsDoorsDiameterWithItsMemberCounts() {
        var designs = SheetSizing.designs(all.sheet(), all.sized());
        var door = com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(all.sheet().units().stream().filter(u -> u.id().equals("T-101")).findFirst().orElseThrow().params());
        var column = designs.stream().filter(d -> d.column() != null).findFirst().orElseThrow();
        assertEquals("T-101", column.tag());
        assertEquals(door.diameter(), column.column().diameter(), 1e-12);
        assertEquals(door.units(), column.column().components().units());
        var condenser = designs.stream().filter(d -> d.member().equals("condenser")).findFirst().orElseThrow();
        assertEquals(5, condenser.count());
        long shaped = all.sized().units().values().stream().filter(u -> u.size() != null && !(u.size() instanceof com.wormzjl.icheme.science.flowsheet.sizing.ValveSizing.Size)).count();
        assertEquals(shaped, designs.size());
        var read = com.wormzjl.icheme.world.level.block.entity.SentDesignTags.read(com.wormzjl.icheme.world.level.block.entity.SentDesignTags.write(designs, List.of()));
        assertTrue(read.whole(), read.repairs().toString());
        assertEquals(designs, read.designs());
    }

    /** A run that has just ended rates every size with no trial column: each column reads its own run's trays. */
    @Test
    void aRatingSolvesNoColumn() {
        var solved = new AtomicBoolean();
        var watching = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
            if (StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> f.getClassName().contains("V3ColumnCalculator")))) solved.set(true);
        });
        var rated = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.rate(all.sheet(), all.run(), watching));
        assertFalse(solved.get());
        assertEquals(all.sized().units().keySet(), rated.units().keySet());
        all.sized().units().forEach((id, unit) -> assertEquals(unit.size(), rated.units().get(id).size(), id));
    }
}
