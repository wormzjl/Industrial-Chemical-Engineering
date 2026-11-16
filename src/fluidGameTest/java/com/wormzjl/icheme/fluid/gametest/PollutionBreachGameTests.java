package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.world.level.block.EquipmentProtection;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A blast breaches constructed equipment (batch 2026-11-15-pollution-system, WP6b): a 3 m drum holding nitrogen, an explosion
 * of power 4 (a TNT block's) 2 m from its west shell that breaks no block ({@link Level.ExplosionInteraction#NONE}). Within
 * {@link #BREACHED_WITHIN} ticks the drum's spec is breached, no cell of it is gone, and its graph inventory stands open
 * once the island applies the queued edit; a second breach queues nothing, and the repair ({@link FluidWorldAuthority#repair},
 * the repair kit's path) closes it again. The drum is removed at the end, its registration with it.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class PollutionBreachGameTests {
    private PollutionBreachGameTests() {}

    /** Ticks from the blast to the breached spec: the pollution tick applies the blast's breaches in the same tick. */
    private static final int BREACHED_WITHIN = 40;

    private static PhysicalFluidTopology.Position position(ServerLevel level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    private static BlockPos cell(BlockPos origin, ShapeRasterizer.Cell c) {
        return origin.offset(c.x(), c.y(), c.z());
    }

    /** Vessel {@code id}'s graph node: its moles, and whether its inventory stands open; null while no island holds it. */
    private static PassiveNetwork.Reservoir node(FluidWorldAuthority world, long id) {
        for (var s : world.diagnosticSnapshots())
            for (var n : s.graph().reservoirs())
                if (n.id() == id && n.kind() == PassiveNetwork.NodeKind.RESERVOIR) return n;
        return null;
    }

    private static double moles(FluidWorldAuthority world, long id) {
        var n = node(world, id);
        return n == null ? Double.NaN : Arrays.stream(n.inventory().moles()).sum();
    }

    @GameTest(template = "empty", timeoutTicks = 1600, batch = "pollution_breach")
    public static void aBlastBreachesADrumAndTheRepairClosesIt(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var service = PollutionService.find(level.getServer()).orElseThrow();
        helper.assertTrue(service.enabled(), "Pollution runs in the GameTest server");
        var shape = EquipmentShape.drum(3, 3, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 0);
        var origin = helper.absolutePos(new BlockPos(4, 1, 4));
        var layout = ShapeRasterizer.layout(shape);
        var cells = new ArrayList<BlockPos>();
        for (var list : List.of(layout.shell(), layout.interior())) for (var c : list) cells.add(cell(origin, c));
        var node = cell(origin, ShapeRasterizer.fluidCell(shape));
        var nozzle = ShapeRasterizer.ports(shape).stream().max(Comparator.comparingInt(ShapeRasterizer.Port::dy)).orElseThrow();
        var face = Direction.fromDelta(nozzle.dx(), nozzle.dy(), nozzle.dz());
        EquipmentStructure.place(level, origin, shape, Paint.DEFAULT, Integer.MAX_VALUE);
        long id = world.at(position(level, node)).orElseThrow().device().id();
        // Some nitrogen in the drum through its top nozzle, from a 2 bar generator one pipe above it.
        var pipe = cell(origin, nozzle.cell()).relative(face);
        var generator = pipe.relative(face);
        level.setBlock(generator, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), 3);
        var nitrogen = FluidDeviceSpec.nitrogen(com.wormzjl.icheme.science.material.MaterialRuntime.active()).composition();
        TankCharge.run(world, ((FluidDeviceBlockEntity) level.getBlockEntity(generator)).fluidIdentity(), new FluidDeviceSpec(1, 298.15, 200000, nitrogen));
        level.setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        // The blast's centre 2 m west of the west shell's cell centres, at the drum's middle height: strength 3 there.
        var blast = origin.getCenter().add(-2, 1, 1);
        String[] failure = {null};
        long[] blasted = {0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(moles(world, id) > 10, "Waiting for nitrogen in the drum"))
                .thenExecute(() -> {
                    level.removeBlock(generator, false);
                    level.removeBlock(pipe, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.capture().world().events().isEmpty(), "Waiting for the drum to stand alone"))
                .thenExecute(() -> {
                    if (world.isBreached(id)) failure[0] = "the drum was breached before the blast";
                    blasted[0] = helper.getTick();
                    level.explode(null, blast.x, blast.y, blast.z, 4, Level.ExplosionInteraction.NONE);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.isBreached(id), "Waiting for the breach"))
                .thenExecute(() -> {
                    long took = helper.getTick() - blasted[0];
                    if (failure[0] == null && took > BREACHED_WITHIN) failure[0] = "breached " + took + " ticks after the blast, at most " + BREACHED_WITHIN;
                    for (var at : cells)
                        if (failure[0] == null && !EquipmentProtection.cell(level.getBlockState(at))) failure[0] = "the blast removed the cell at " + at.subtract(origin).toShortString();
                    if (failure[0] == null && world.breach(id)) failure[0] = "a second breach queued another edit";
                })
                .thenWaitUntil(() -> {
                    var n = node(world, id);
                    helper.assertTrue(n != null && n.inventory().open() && !n.inventory().rigid(), "Waiting for the island to stand the breached drum open");
                })
                .thenExecute(() -> {
                    if (failure[0] == null && !world.repair(id)) failure[0] = "the repair queued nothing";
                    if (failure[0] == null && world.repair(id)) failure[0] = "a second repair queued another edit";
                })
                .thenWaitUntil(() -> {
                    var n = node(world, id);
                    helper.assertTrue(!world.isBreached(id) && n != null && !n.inventory().open(), "Waiting for the repaired drum to close");
                })
                .thenExecute(() -> EquipmentTestCleanup.remove(level, node))
                .thenWaitUntil(() -> helper.assertTrue(world.at(position(level, node)).isEmpty() && world.structure(position(level, origin)) == null, "Waiting for the drum and its record to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }
}
