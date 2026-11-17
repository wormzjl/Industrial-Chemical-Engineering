package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.testworld.CrudeUnitPlant;
import static com.wormzjl.icheme.testworld.CrudeUnitPlant.*;
import com.wormzjl.icheme.testworld.CrudeUnitPlant.Options;
import com.wormzjl.icheme.testworld.CrudeUnitPlant.Rig;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.runtime.assembly.AssemblyService;
import com.wormzjl.icheme.runtime.assembly.AssemblyStore;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.science.plant.column.OperationView;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.Proposal;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialAxis;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A world to try the column's Operation tab in by hand (batch 2026-10-28-column-operation): the default column template
 * (Tia Juana Light: 40 trays, three side draws, three pumparounds, sump steam) at its full feed ({@link #SIZED_SCALE}; the
 * column tests' fixed plant at a sixteenth of its feed on a quarter of its column area), built with a water-cooled condenser (C6b), its receiver, a reflux pump and each pumparound's pump and
 * exchanger. The column's diameter, every unit and the boundary lines' bores are the process simulator's Size all on the same
 * column ({@link #sized}, batch 2026-11-16-review-plant-sizing); the column tests keep the fixed units of {@link Options#REVIEW}. The feed and the steam come from generators, the
 * condenser's and each pumparound cooler's water from a generator into a void (W4: the column owes them their duties);
 * every product line ends at a void at the atmosphere. The build validates the plant complete, starts the column and
 * leaves it running.
 *
 * <p>Not a gate: its namespace runs only in {@code runReviewWorldServer}, which leaves the world in
 * {@code run/review-world/world} to be copied into a client's {@code saves}.
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class ColumnOperationReviewWorld {
    private ColumnOperationReviewWorld() {}

    /**
     * The reports of the running column: their number and the online ticks between them. A GameTest server runs its ticks as
     * fast as it can, far ahead of the solves a cycle and its islands wait for (plan section 9), and an island behind the
     * clock plans from the column's past: from the start on, the test holds the server to 20 ticks a second, as a game runs.
     */
    static final int REPORTS = 4, REPORT_TICKS = 600, PREHEAT_REPORTS = 30, STEADY_REPORTS = 3;

    @GameTest(template = "empty", timeoutTicks = Integer.MAX_VALUE, batch = "review-operation")
    public static void aCrudeColumnRuns(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "review-operation")) return;
        var rig = rig(helper, review(MaterialRuntime.active()));
        var plant = rig.plant;
        String[] failure = {null};
        try {
            rig.build();
        } catch (RuntimeException e) {
            failure[0] = "build: " + e.getMessage();
            IChemE.LOGGER.error("review_operation build failed", e);
        }
        var sequence = helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || plant.valvesInstalled(), "Waiting for the valves"))
                .thenIdle(20)
                .thenExecute(() -> {
                    // Every nozzle a line starts or ends at is met as the player sees it: the pipe's block joined towards
                    // it, its face flanged, the nozzle at the pipe's size (NOZZLE_JOIN_REVIEW.md), not only a block there.
                    if (failure[0] == null) {
                        var unjoined = plant.unjoined();
                        if (unjoined != null) failure[0] = "unjoined nozzles: " + unjoined;
                    }
                })
                // The charge pump's drive holds its flow (PUMP_DRIVE_FLOW_PLAN.md): once its train has compiled, the pump's set
                // flow, the drive stopped in REGULATE_FLOW (the motor on FE wired to the pump by a data cable), then the heaters.
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || rig.chargeDriven(), "Waiting for the charge pump's drive"))
                .thenExecute(() -> {
                    if (failure[0] == null) rig.holdCharge(failure);
                    if (failure[0] == null) rig.light(failure);
                })
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || rig.paced() && rig.fueled(), "Waiting for the heaters' fuel"))
                // The furnace lit before any feed (owner, 2026-11-17): the crude enters the empty train only now, through
                // lit burners, and the column starts once the charge line is full and hot (or after HOT_TICKS, logged).
                .thenExecute(() -> {
                    if (failure[0] == null && rig.chargeEnd != null) rig.openCrude();
                })
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || rig.paced() && rig.warmed(), "Waiting for the hot charge"))
                .thenExecute(() -> {
                    if (failure[0] == null) rig.start(failure);
                    if (failure[0] == null) rig.spawn();
                })
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || rig.paced() && rig.past(), "Waiting for the first solve"));
        // The crude unit fills its preheat train before the tower sees a feed: it reports for longer.
        int reports = rig.options.preheat() == null ? REPORTS : PREHEAT_REPORTS;
        // The reports end early once the column has run on a solved table for STEADY_REPORTS reports in a row.
        int[] steady = {0};
        for (int i = 1; i <= reports; i++) {
            int n = i;
            int[] ticks = {0};
            sequence = sequence.thenWaitUntil(() -> helper.assertTrue(failure[0] != null || steady[0] >= STEADY_REPORTS || rig.paced() && ++ticks[0] >= REPORT_TICKS, "Waiting for the column"))
                    .thenExecute(() -> {
                        if (failure[0] == null && steady[0] < STEADY_REPORTS) steady[0] = rig.report(n) ? steady[0] + 1 : 0;
                        if (failure[0] == null && rig.chargeFault != null) failure[0] = rig.chargeFault;
                    });
        }
        sequence.thenExecute(() -> {
            if (failure[0] == null && rig.chargePump != null && !rig.chargeChecked)
                failure[0] = "the charge pump's check was never reached: no report came " + Rig.CHARGE_TICKS + " online ticks after its first flow (first flow at "
                        + (rig.chargeFlowing < 0 ? "none" : "t=" + rig.chargeFlowing) + ")";
            plant.release();
            if (failure[0] != null) helper.fail(failure[0]);
        }).thenSucceed();
    }

    /**
     * The plant's rig north of the test's own structure block, on clear ground. The test grid grows east and south from its
     * first test, and the structures of the tests that ran before stay standing: where the test's own row is not the first,
     * the plant's box reaches back over earlier rows. The rig moves north a plant's depth at a time until no block stands
     * above the ground anywhere in its box.
     */
    static Rig rig(GameTestHelper helper) {
        return rig(helper, Options.REVIEW);
    }

    /** {@link #rig(GameTestHelper)} built as {@code options} say, its box and its first place from them. */
    static Rig rig(GameTestHelper helper, Options options) {
        var level = helper.getLevel();
        var layout = options.layout();
        var corner = helper.absolutePos(layout.corner());
        var below = layout.below();
        var above = layout.above();
        for (int k = 0; k <= 16; k++) {
            var at = corner.north(k * (above.getZ() - below.getZ() + 1));
            level.getChunk(at.getX() >> 4, at.getZ() >> 4);
            int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ());
            if (clear(level, at.offset(below), at.offset(above), ground))
                return CrudeUnitPlant.rig(level, new BlockPos(at.getX(), ground, at.getZ()), options);
        }
        throw new IllegalStateException("No clear ground for the column plant north of " + corner.toShortString());
    }


}
