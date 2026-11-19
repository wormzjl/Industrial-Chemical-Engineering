package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.testworld.PlantBuilder;
import com.wormzjl.icheme.world.level.block.Positions;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.wormzjl.icheme.fluid.gametest.FixtureChunks.at;
import static com.wormzjl.icheme.fluid.gametest.FixtureChunks.full;
import static com.wormzjl.icheme.fluid.gametest.FixtureChunks.remote;
import static com.wormzjl.icheme.fluid.gametest.FixtureChunks.ticket;

@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class SecondaryNodeBindingGameTests {
    private SecondaryNodeBindingGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "exchanger-secondary-node")
    public static void bindingJustTheShellSideDoesNotLoadThePrimarySocket(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var shape = PlantBuilder.unit(EquipmentShape.Kind.EXCHANGER);
        var nodes = ShapeRasterizer.fluidCells(shape);
        var a = nodes.getFirst();
        var b = nodes.get(1);
        helper.assertTrue(a.x() != b.x() || a.z() != b.z(), "The two fixture nodes must be horizontally separated");
        var edge = remote(helper).offset(-8, 0, -8);
        var origin = a.x() != b.x()
                ? edge.offset(-Math.max(a.x(), b.x()), 0, 8)
                : edge.offset(8, 0, -Math.max(a.z(), b.z()));
        var primary = at(origin, a);
        var secondary = at(origin, b);
        var primaryChunk = new ChunkPos(primary);
        var secondaryChunk = new ChunkPos(secondary);
        var plant = new PlantBuilder(level, origin, new BlockPos(-2, -2, -2),
                new BlockPos(shape.sizeX() + 2, shape.sizeY() + 2, shape.sizeZ() + 2));
        plant.hold();
        var vessel = plant.place(shape, 0, 0, 0);
        long tube = world.at(Positions.of(level, primary)).orElseThrow().device().id();
        long shell = world.at(Positions.of(level, secondary)).orElseThrow().device().id();
        world.deviceLoaded(tube);
        var savedSocket = ((EquipmentShellBlockEntity) level.getBlockEntity(primary)).instruments().dataPort();
        helper.assertTrue(savedSocket != null && savedSocket.host() == tube && savedSocket.owner().equals(primary),
                "The fixture has no primary data socket");
        level.getChunkSource().save(true);
        plant.release();

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(!full(level, primary) && !full(level, secondary), "Waiting for both node chunks to unload"))
                .thenExecute(() -> ticket(level, secondaryChunk, true))
                .thenWaitUntil(() -> helper.assertTrue(full(level, secondary), "Waiting for the secondary node's chunk"))
                .thenExecute(() -> {
                    helper.assertTrue(!full(level, primary), "The secondary node's chunk load loaded the primary node");
                    world.deviceLoaded(shell);
                    helper.assertTrue(!full(level, primary), "Binding the secondary node requested the primary chunk");
                    helper.assertTrue(savedSocket.equals(world.controls().facts().port(savedSocket.id())), "The primary socket fact changed");
                    var secondaryCell = (EquipmentShellBlockEntity) level.getBlockEntity(secondary);
                    helper.assertTrue(secondaryCell.instruments().dataPort() == null, "A duplicate socket appeared on the secondary node");
                    ticket(level, secondaryChunk, false);
                    ticket(level, primaryChunk, true);
                })
                .thenWaitUntil(() -> helper.assertTrue(full(level, primary), "Waiting for the primary node's own chunk load"))
                .thenExecute(() -> {
                    var cell = (EquipmentShellBlockEntity) level.getBlockEntity(primary);
                    helper.assertTrue(savedSocket.equals(cell.instruments().dataPort()), "The primary node did not bind its saved socket");
                    ticket(level, primaryChunk, false);
                    plant.hold();
                    PlantBuilder.removeStructure(level, vessel.controller());
                    plant.release();
                })
                .thenSucceed();
    }
}
