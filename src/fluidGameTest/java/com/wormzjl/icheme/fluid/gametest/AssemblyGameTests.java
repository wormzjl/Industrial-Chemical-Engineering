package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.testworld.PlantBuilder;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.assembly.AssemblyService;
import com.wormzjl.icheme.runtime.assembly.AssemblyStore;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.assembly.AssemblyReport.State;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Assembly validation in a world (batch 2026-10-26-multi-machine-framework, plan sections 6 and 7, gate W5): a column
 * plant built as its design asks validates complete; a removed pipe reverts the report; a pipe added at an open end keeps a
 * partial one; a reload keeps a complete report without a walk; a dropped store rebuilds with one walk per column. Each
 * test removes what it placed, its result held until the cleanup is done, so a failure leaves nothing registered.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class AssemblyGameTests {
    private AssemblyGameTests() {}

    /**
     * Removes the GameTest framework's start controls from a review world: the command block ({@code test runclosest}) and
     * its button that {@code GameTestInfo.prepareTestStructure} sets beside the structure block, at (1, 0, -1) and (1, 0, -2)
     * in the test's frame. A hand-test world opens in a client that has no {@code /test} command, so they do nothing
     * there, and where they could run they would rebuild the test over the plant. The button first, so it drops nothing.
     */
    static void clearStartControls(GameTestHelper helper) {
        var level = helper.getLevel();
        var button = helper.absolutePos(new BlockPos(1, 0, -2));
        if (level.getBlockState(button).is(net.minecraft.world.level.block.Blocks.STONE_BUTTON))
            level.setBlock(button, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        var command = helper.absolutePos(new BlockPos(1, 0, -1));
        if (level.getBlockState(command).is(net.minecraft.world.level.block.Blocks.COMMAND_BLOCK))
            level.setBlock(command, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    /** A {@link PlantBuilder} at the test's default place: a base at (8, 4, 18) of its frame, its box round it. */
    static PlantBuilder plant(GameTestHelper helper) {
        return plant(helper, helper.absolutePos(new BlockPos(8, 4, 18)), new BlockPos(-8, -3, -18), new BlockPos(32, 22, 14));
    }

    /** A {@link PlantBuilder} in the test's level from {@code base}, its pipes kept inside the box from {@code base + below} to {@code base + above}. */
    static PlantBuilder plant(GameTestHelper helper, BlockPos base, BlockPos below, BlockPos above) {
        return new PlantBuilder(helper.getLevel(), base, below, above);
    }

    /** A step of a test on its plant: it records a failure in {@code failure[0]}. */
    @FunctionalInterface
    private interface Step {
        void run(PlantBuilder plant, String[] failure);
    }

    /**
     * Builds a plant, waits for its valves, runs {@code steps} 20 ticks apart (each only while none failed), then removes
     * the plant and waits for it to leave; only then does the test pass or fail.
     */
    private static void withPlant(GameTestHelper helper, boolean offgasTank, Step... steps) {
        withPlant(helper, plant -> plant.build(offgasTank), steps);
    }

    /** {@link #withPlant} with the plant built by {@code build}. */
    private static void withPlant(GameTestHelper helper, java.util.function.Consumer<PlantBuilder> build, Step... steps) {
        var plant = plant(helper);
        String[] failure = {null};
        var sequence = helper.startSequence();
        try {
            build.accept(plant);
            sequence = sequence.thenWaitUntil(() -> helper.assertTrue(plant.valvesInstalled(), "Waiting for the valves"));
            for (int i = 0; i < steps.length; i++) {
                var step = steps[i];
                if (i > 0) sequence = sequence.thenIdle(20);
                sequence = sequence.thenExecute(() -> {
                    if (failure[0] != null) return;
                    try {
                        step.run(plant, failure);
                    } catch (RuntimeException e) {
                        failure[0] = e.toString();
                    }
                });
            }
        } catch (RuntimeException e) {
            failure[0] = "build: " + e.getMessage();
            com.wormzjl.icheme.IChemE.LOGGER.error("assembly_gametest build failed", e);
        }
        sequence.thenExecute(plant::remove).thenWaitUntil(() -> helper.assertTrue(plant.removed(), "Waiting for the plant to leave"))
                .thenExecute(() -> {
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                }).thenSucceed();
    }

    /** Validates the plant's column; a report that is not complete is a failure. */
    private static void complete(PlantBuilder plant, String[] failure) {
        var entry = AssemblyService.validate(plant.level, plant.controller());
        if (entry == null || entry.status() != AssemblyStore.Status.COMPLETE) failure[0] = "not complete: " + plant.faults();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-complete")
    public static void aPlantBuiltAsDesignedValidatesComplete(GameTestHelper helper) {
        withPlant(helper, true, (plant, failure) -> {
            complete(plant, failure);
            if (failure[0] == null && plant.entry().report().slots().size() != ColumnBlueprint.of(plant.column.shape().trays()).slots().size())
                failure[0] = "slots: " + plant.entry().report().slots();
            // Every nozzle a line starts or ends at is met as the player sees it, the column's too (NOZZLE_JOIN_REVIEW.md).
            if (failure[0] == null) failure[0] = plant.unjoined();
        });
    }

    /**
     * A click on a unit of the plant opens nothing of the column's before a validation holds it; after it, the column's GUI
     * with the unit selected, found through a shell cell of the column (plan section 10). A click on the column opens its own.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-click")
    public static void aClickOnAMemberOpensItsColumnsGuiWithTheMemberSelected(GameTestHelper helper) {
        withPlant(helper, true, (plant, failure) -> {
            if (com.wormzjl.icheme.network.AssemblyNetwork.target(plant.level, plant.drum) != null) {
                failure[0] = "a drum no report holds opens a column";
                return;
            }
            complete(plant, failure);
            if (failure[0] != null) return;
            var origin = plant.column.origin();
            var drum = com.wormzjl.icheme.network.AssemblyNetwork.target(plant.level, plant.drum);
            var own = com.wormzjl.icheme.network.AssemblyNetwork.target(plant.level, plant.column);
            if (drum == null || !drum.origin().equals(origin) || drum.selected() != plant.drum.origin().asLong()) failure[0] = "drum: " + drum;
            else if (own == null || !own.origin().equals(origin) || own.selected() != com.wormzjl.icheme.science.assembly.AssemblyReport.NONE) failure[0] = "column: " + own;
            else for (var target : List.of(drum, own)) {
                var found = EquipmentStructure.find(plant.level, target.column());
                if (found == null || !found.origin().equals(origin)) failure[0] = "the column cell " + target.column() + " finds " + found;
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-removed-pipe")
    public static void aRemovedPipeRevertsACompleteReport(GameTestHelper helper) {
        withPlant(helper, true, (plant, failure) -> {
            complete(plant, failure);
            if (failure[0] != null) return;
            // The off-gas line's pipe beyond its valve leaves: the report relied on it.
            plant.level.removeBlock(plant.offgasEnd, false);
            plant.devices.remove(plant.offgasEnd);
        }, (plant, failure) -> {
            var after = plant.entry();
            if (after == null || after.status() != AssemblyStore.Status.NOT_VALIDATED) failure[0] = "not reverted: " + (after == null ? null : after.status());
        });
    }

    /**
     * W8 (batch 2026-10-27-heat-exchanger-design, plan section 7): an exchanger whose shell side holds an outlet temperature
     * asks for a bypass. Its built bypass validates complete; its side's lines name the bypass valve, which, bare (no
     * flowmeter), cannot regulate yet; the tube side has none. A removed bypass pipe reverts the report and the lines.
     */
    @GameTest(template = "empty", timeoutTicks = 800, batch = "assembly-exchanger")
    public static void anExchangersBypassValidatesAndARemovedPipeReverts(GameTestHelper helper) {
        withPlant(helper, PlantBuilder::buildBypass, (plant, failure) -> {
            long shell = plant.side(HeatSides.Side.SHELL);
            var r = plant.world.registrations().get(shell);
            plant.world.edit(shell, r.revision(), r.device().withHeat(r.device().heat().withControl(new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE, 318.15, 0, 0))), r.spec());
        }, (plant, failure) -> {
            complete(plant, failure);
            if (failure[0] != null) return;
            var lines = plant.world.lines(plant.side(HeatSides.Side.SHELL));
            long valve = plant.world.at(plant.position(plant.bypassValve)).orElseThrow().device().id();
            if (lines == null || lines.status() != FluidWorldAuthority.Lines.Status.UNMETERED || lines.valve() != valve) failure[0] = "shell lines: " + lines + ", the valve " + valve;
            else if (plant.world.lines(plant.side(HeatSides.Side.TUBE)) != null) failure[0] = "the tube side has lines: " + plant.world.lines(plant.side(HeatSides.Side.TUBE));
        }, (plant, failure) -> {
            plant.level.removeBlock(plant.bypassPipe, false);
            plant.devices.remove(plant.bypassPipe);
        }, (plant, failure) -> {
            var after = plant.entry();
            var lines = plant.world.lines(plant.side(HeatSides.Side.SHELL));
            if (after == null || after.status() != AssemblyStore.Status.NOT_VALIDATED) failure[0] = "not reverted: " + (after == null ? null : after.status());
            else if (lines == null || lines.status() != FluidWorldAuthority.Lines.Status.NOT_VALIDATED) failure[0] = "lines not reverted: " + lines;
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-open-end")
    public static void aPipeAddedAtAnOpenEndKeepsAPartialReport(GameTestHelper helper) {
        var before = new AssemblyStore.Entry[1];
        withPlant(helper, false, (plant, failure) -> {
            var entry = AssemblyService.validate(plant.level, plant.controller());
            if (entry == null || entry.status() != AssemblyStore.Status.CHECKED || entry.report().slot(ColumnBlueprint.OFFGAS_VALVE).state() != State.OPEN_END) {
                failure[0] = "expected a checked report with the off-gas line open: " + plant.faults();
                return;
            }
            before[0] = entry;
            plant.pipe(plant.offgasEnd.relative(PlantBuilder.out(plant.drum, Role.VAPOUR_OUTLET)));
        }, (plant, failure) -> {
            if (!before[0].equals(plant.entry())) failure[0] = "the partial report changed: " + plant.faults();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-reload")
    public static void aReloadKeepsACompleteReportWithoutAWalk(GameTestHelper helper) {
        var walks = new long[1];
        withPlant(helper, true, (plant, failure) -> {
            complete(plant, failure);
            if (failure[0] != null) return;
            // The store saved and read back, as a reload does; the column's controller and the plant's chunks load again.
            var registries = plant.level.registryAccess();
            var store = AssemblyStore.of(plant.level);
            plant.level.getDataStorage().set(AssemblyStore.DATA_NAME, AssemblyStore.load(store.save(new CompoundTag(), registries), registries));
            walks[0] = AssemblyService.walks(plant.level.getServer());
            ((EquipmentShellBlockEntity) plant.level.getBlockEntity(plant.controller())).onLoad();
            for (var chunk : plant.chunks()) AssemblyService.chunkLoaded(plant.level, chunk);
        }, (plant, failure) -> {
            var entry = plant.entry();
            if (entry == null || entry.status() != AssemblyStore.Status.COMPLETE) failure[0] = "not kept: " + plant.faults();
            else if (AssemblyService.walks(plant.level.getServer()) != walks[0]) failure[0] = "a reload walked";
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-rebuild")
    public static void aDroppedStoreRebuildsWithOneWalkPerColumn(GameTestHelper helper) {
        var walks = new long[1];
        withPlant(helper, true, (plant, failure) -> {
            complete(plant, failure);
            if (failure[0] != null) return;
            // A store of another state format reads as dropped; the column's controller loads.
            var registries = plant.level.registryAccess();
            var tag = AssemblyStore.of(plant.level).save(new CompoundTag(), registries);
            tag.putInt("format", AssemblyStore.FORMAT + 1);
            plant.level.getDataStorage().set(AssemblyStore.DATA_NAME, AssemblyStore.load(tag, registries));
            walks[0] = AssemblyService.walks(plant.level.getServer());
            ((EquipmentShellBlockEntity) plant.level.getBlockEntity(plant.controller())).onLoad();
        }, (plant, failure) -> {
            var entry = plant.entry();
            if (entry == null || entry.status() != AssemblyStore.Status.COMPLETE) failure[0] = "not rebuilt: " + plant.faults();
            else if (AssemblyService.walks(plant.level.getServer()) != walks[0] + 1) failure[0] = "walks: " + (AssemblyService.walks(plant.level.getServer()) - walks[0]);
            // A later load finds the entry: no second walk.
            ((EquipmentShellBlockEntity) plant.level.getBlockEntity(plant.controller())).onLoad();
        }, (plant, failure) -> {
            if (AssemblyService.walks(plant.level.getServer()) != walks[0] + 1) failure[0] = "a second load walked";
            // The level keeps a store that is not rebuilding: later runs reuse this world.
            plant.level.getDataStorage().set(AssemblyStore.DATA_NAME, new AssemblyStore());
        });
    }
}
