package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.item.PipelineToolItem;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.PipeAnchor;
import com.wormzjl.icheme.world.level.block.PipeLaying;
import com.wormzjl.icheme.world.level.block.PipeRackBlock;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.NozzleBore;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.PipeRoute;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.RackGeometry;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The laying tool's server commands in a real world (batch 2026-10-26-pipe-laying-system, W2): a line laid between two
 * tanks joins them and nothing it runs beside, a refused lay changes nothing, and a dismantle removes one block whose
 * neighbours stay open ends that a new line joins again.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class PipeLayingGameTests {
    private static final int ALONG_X = 1 << Direction.WEST.ordinal() | 1 << Direction.EAST.ordinal();
    /** RAL 5015 sky blue and RAL 3000 flame red: the primary and secondary paints the rack test lays its line in. */
    private static final int SKY_BLUE = 0x2271B3, FLAME_RED = 0xA72920;

    private PipeLayingGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "pipe-laying")
    public static void aLaidLineJoinsItsTanksOnlyAndComesOutOneBlockAtATime(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var west = helper.absolutePos(new BlockPos(0, 1, 1));
        var east = west.east(5);
        var beside = west.east(2).south();
        var cells = List.of(west.east(), west.east(2), west.east(3), west.east(4));
        var placed = new ArrayList<BlockPos>(List.of(west, east, beside));
        placed.addAll(cells);
        // The line runs past the empty template's barrier ring: clear its path, as the other line tests overwrite it.
        for (var cell : cells) level.setBlock(cell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(west, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        level.setBlock(east, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        // A hand-placed pipe beside the path: it joins every neighbour that has a joint towards it, and a laid line has none.
        level.setBlock(beside, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "PipeLayingTest"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(west.getX() + 2.5, west.getY(), west.getZ() - 1.5);
        var tool = new ItemStack(ModItems.PIPE_LAYING_TOOL.get());
        tool.set(ModItems.PIPE_LAYING_SETTINGS.get(), new PipelineToolItem.Settings(.1));
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        player.getInventory().add(new ItemStack(ModItems.FLUID_PIPE.get(), 3));
        try {
            int registered = world.registrations().size();
            helper.assertTrue(cells.stream().allMatch(c -> level.getBlockState(c).canBeReplaced()), "The path is not clear: " + cells.stream().map(c -> c.toShortString() + "=" + level.getBlockState(c)).toList());
            var refused = lay(player, west, Direction.EAST, east, Direction.WEST, cells);
            helper.assertTrue(refused != null && refused.getString().equals(PipeLaying.message("items", 4).getString()), "Laid four blocks with three pipe items: " + refused);
            helper.assertTrue(level.getBlockState(cells.getFirst()).isAir() && world.registrations().size() == registered,
                    "A refused lay changed the world: " + level.getBlockState(cells.getFirst()) + ", registrations " + registered + " -> " + world.registrations().size());
            player.getInventory().add(new ItemStack(ModItems.FLUID_PIPE.get(), 1));
            helper.assertTrue(lay(player, west, Direction.EAST, east, Direction.WEST, List.of(cells.get(0), cells.get(2), cells.get(3))) != null,
                    "A route with a gap was laid");
            helper.assertTrue(lay(player, west, Direction.EAST, east, Direction.WEST, cells) == null, "The line was refused");
            helper.assertTrue(world.registrations().size() == registered + 4, "Not one registration per laid block");
            helper.assertTrue(player.getInventory().countItem(ModItems.FLUID_PIPE.get()) == 0, "The pipe items were not taken");
            for (var cell : cells) {
                var record = world.at(position(level, cell)).orElseThrow();
                helper.assertTrue(record.device().kind() == TopologyCompiler.Kind.PIPE && record.device().joints() == ALONG_X && record.device().geometry().diameter() == .1,
                        "Laid block " + cell.toShortString() + " registered as " + record.device());
                helper.assertTrue(level.getBlockEntity(cell) instanceof FluidDeviceBlockEntity entity && entity.fluidIdentity() == record.device().id() && entity.joints() == ALONG_X && entity.openEnds() == 0 && entity.paint() == Paint.BARE && entity.secondary() == Paint.BARE,
                        "Laid block " + cell.toShortString() + " not bound to its registration");
                helper.assertTrue(connected(level, cell, Direction.WEST) && connected(level, cell, Direction.EAST), "Laid block " + cell.toShortString() + " not joined along the line");
            }
            helper.assertTrue(!connected(level, cells.get(1), Direction.SOUTH) && !connected(level, beside, Direction.NORTH), "The line joined the pipe it runs beside");
            helper.assertTrue(world.at(position(level, beside)).orElseThrow().device().joints() == PhysicalFluidTopology.ALL_FACES, "The hand-placed pipe lost its automatic joints");

            // One block out at once: its neighbours keep their joints towards it and show open ends.
            helper.assertTrue(LayingTestCommands.dismantle(player, InteractionHand.MAIN_HAND, new PipeAnchor.Click(cells.get(1), Direction.UP)) == null, "The dismantle was refused");
            helper.assertTrue(level.getBlockState(cells.get(1)).isAir() && world.at(position(level, cells.get(1))).isEmpty(), "The dismantled block remains");
            helper.assertTrue(world.at(position(level, cells.get(0))).orElseThrow().device().joints() == ALONG_X && !connected(level, cells.get(0), Direction.EAST),
                    "The neighbour did not become an open end");
            helper.assertTrue(((FluidDeviceBlockEntity) level.getBlockEntity(cells.get(0))).openEnds() == 1 << Direction.EAST.ordinal(), "The open end is not marked towards the gap");
            helper.assertTrue(LayingTestCommands.dismantle(player, InteractionHand.MAIN_HAND, new PipeAnchor.Click(west, Direction.UP)) != null, "Dismantled a tank");

            // A one-block line between the two open ends joins them again; the ends need no edit.
            player.getInventory().add(new ItemStack(ModItems.FLUID_PIPE.get(), 1));
            helper.assertTrue(lay(player, cells.get(0), Direction.EAST, cells.get(2), Direction.WEST, List.of(cells.get(1))) == null, "The gap was not refilled");
            for (var cell : cells)
                helper.assertTrue(connected(level, cell, Direction.WEST) && connected(level, cell, Direction.EAST), "Block " + cell.toShortString() + " not joined after the refill");
            helper.killAllEntities();
            helper.succeed();
        } finally {
            for (var at : placed) level.removeBlock(at, false);
        }
    }

    /**
     * W3: the route between two tanks follows three rack cells and turns in the corner one; it lays as plain pipes at
     * both ends and lanes in the racks, and joins the tanks into one island. A rack that carries a lane is not broken;
     * one lane comes out alone, and a one-cell lane between the two open ends it leaves joins them again.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "pipe-laying")
    public static void aLineThroughARackCornerJoinsItsTanksAndALaneComesOutAlone(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var west = helper.absolutePos(new BlockPos(0, 1, 1));
        var first = west.east();
        var straight = west.east(2);
        var corner = west.east(3);
        var turned = corner.south();
        var last = turned.south();
        var south = last.south();
        var placed = List.of(west, first, straight, corner, turned, last, south);
        for (var at : placed) level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(west, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        level.setBlock(south, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        // Placed as a player places them (RACK_JUNCTION_PLAN J2), each facing the look: one looking east; the next at its
        // end looking south, a corner from the run towards the look; one more south of it, on along the turned run.
        var racks = ModBlocks.PIPE_RACK.get();
        level.setBlock(straight, racks.placed(level, straight, Direction.EAST, false), 3);
        level.setBlock(corner, racks.placed(level, corner, Direction.SOUTH, false), 3);
        level.setBlock(turned, racks.placed(level, turned, Direction.SOUTH, false), 3);
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "PipeRackTest"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(west.getX() + 2.5, west.getY(), west.getZ() - 1.5);
        var tool = new ItemStack(ModItems.PIPE_LAYING_TOOL.get());
        tool.set(ModItems.PIPE_LAYING_SETTINGS.get(), new PipelineToolItem.Settings(.1));
        // A painted tool: its lines take the paints, the pipes and lanes they end at keep theirs.
        tool.set(ModItems.PIPE_LAYING_PAINT.get(), SKY_BLUE);
        tool.set(ModItems.PIPE_LAYING_SECONDARY.get(), FLAME_RED);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        player.getInventory().add(new ItemStack(ModItems.FLUID_PIPE.get(), 6));
        try {
            helper.assertTrue(level.getBlockState(straight).getValue(PipeRackBlock.SHAPE) == PipeRackBlock.Shape.STRAIGHT
                    && level.getBlockState(corner).getValue(PipeRackBlock.SHAPE) == PipeRackBlock.Shape.FRONT_RIGHT
                    && level.getBlockState(corner).getValue(PipeRackBlock.FACING) == Direction.SOUTH
                    && level.getBlockState(turned).getValue(PipeRackBlock.SHAPE) == PipeRackBlock.Shape.STRAIGHT, "The racks did not take their shapes: " + level.getBlockState(corner));
            // The route the client searches: into the rack run, round its corner, out to the far tank.
            var start = new PipeAnchor.Click(west, Direction.EAST);
            var end = new PipeAnchor.Click(south, Direction.NORTH);
            double outside = com.wormzjl.icheme.science.equipment.PipeSizes.outside(.1);
            var from = PipeAnchor.resolve(level, start, outside).anchor();
            var to = PipeAnchor.resolve(level, end, outside).anchor();
            // Aimed on the empty straight rack's tier (owner 2026-10-26, "Aim on the tier"): near its east face and its
            // left (north) side, the slot is position 0, passing through the east face and open at the west.
            var aimed = PipeAnchor.resolve(level, new PipeAnchor.Click(straight, Direction.UP, new net.minecraft.world.phys.Vec3(.9, .37, .3)), outside).anchor();
            helper.assertTrue(aimed != null && aimed.kind() == PipeAnchor.Kind.RACK && new RackGeometry.Aim(0, Direction.EAST.ordinal(), Direction.WEST.ordinal(), true).equals(aimed.aim())
                    && aimed.leaving() == Direction.EAST.ordinal(), "The aimed slot: " + aimed);
            var route = PipeRoute.search(from.end(), from.leaving(), to.end(), outside, PipeAnchor.space(level), PipeRoute.Limits.DEFAULT);
            var path = List.of(first, straight, corner, turned, last);
            helper.assertTrue(route.found() && route.cells().equals(path.stream().map(PipeAnchor::cell).toList()), "The route does not follow the racks: " + route);
            // One tier lane through the run and round the corner, each rack numbering it from its own facing's left.
            var options = route.options();
            helper.assertTrue(options.get(0) == -1 && options.get(4) == -1 && options.subList(1, 4).stream().allMatch(o -> o >= 0 && RackGeometry.tier(o) && RackGeometry.level(o) == RackGeometry.TIER),
                    "Not one lane through the racks: " + options);
            helper.assertTrue(LayingTestCommands.lay(player, InteractionHand.MAIN_HAND, start, end, path, route.options()) == null, "The line was refused");
            for (var cell : List.of(first, last))
                helper.assertTrue(level.getBlockEntity(cell) instanceof FluidDeviceBlockEntity pipe && pipe.paint() == SKY_BLUE && pipe.secondary() == FLAME_RED, "Laid pipe " + cell.toShortString() + " not painted");
            for (var cell : List.of(straight, corner, turned)) {
                var lane = ((PipeRackBlockEntity) level.getBlockEntity(cell)).lanes().getFirst();
                helper.assertTrue(lane.paint() == SKY_BLUE && lane.secondary() == FLAME_RED, "Lane at " + cell.toShortString() + " not painted");
            }
            // Aimed at the laid lane's slot from under the tier, the nearest free slot is taken instead.
            var laid = ((PipeRackBlockEntity) level.getBlockEntity(straight)).lanes().getFirst();
            double across = RackGeometry.centre(RackGeometry.position(laid.option()), laid.width());
            var beside = PipeAnchor.resolve(level, new PipeAnchor.Click(straight, Direction.UP, new net.minecraft.world.phys.Vec3(.9, .37, across)), outside).anchor();
            helper.assertTrue(beside != null && beside.aim() != null && beside.aim().option() != laid.option() && beside.aim().fits(), "Aimed at a taken slot: " + beside);
            helper.assertTrue(player.getInventory().countItem(ModItems.FLUID_PIPE.get()) == 1, "Not one pipe item per cell, lanes included");
            int[] expected = {1 << Direction.WEST.ordinal() | 1 << Direction.EAST.ordinal(), 1 << Direction.WEST.ordinal() | 1 << Direction.SOUTH.ordinal(),
                    1 << Direction.NORTH.ordinal() | 1 << Direction.SOUTH.ordinal()};
            for (int i = 0; i < 3; i++) {
                var rack = (PipeRackBlockEntity) level.getBlockEntity(path.get(i + 1));
                int option = options.get(i + 1);
                helper.assertTrue(rack.lanes().size() == 1 && rack.lanes().getFirst().option() == option && rack.lanes().getFirst().joints() == expected[i]
                        && rack.drawnLanes().getFirst().joined() == expected[i], "Rack " + path.get(i + 1).toShortString() + " lanes " + rack.lanes() + " drawn " + rack.drawnLanes());
                var record = world.registrations().get(rack.lanes().getFirst().identity());
                helper.assertTrue(record != null && record.device().position().lane() == option + 1 && record.device().slots() == 1
                        && record.device().points() == RackGeometry.points(rack.rack(), rack.drawnLanes().getFirst()), "Lane not registered on its option and points: " + record);
            }
            helper.assertTrue(connected(level, first, Direction.WEST) && connected(level, first, Direction.EAST) && connected(level, last, Direction.NORTH) && connected(level, last, Direction.SOUTH),
                    "The plain ends do not join the tanks and the lanes");
            // W4: the plain ends are transition pipes to the lane's point, off centre as every resting 4" line's is; a tee
            // there would cut the lane off.
            for (var at : List.of(first, last))
                helper.assertTrue(level.getBlockEntity(at) instanceof FluidDeviceBlockEntity pipe && pipe.transition(), "Not a transition pipe at " + at.toShortString());
            helper.assertTrue(PipeAnchor.resolve(level, new PipeAnchor.Click(first, Direction.UP), outside).refusal() == PipeAnchor.Refusal.TRANSITION, "A tee beside a rack lane was offered");
            long westTank = world.at(position(level, west)).orElseThrow().device().id(), southTank = world.at(position(level, south)).orElseThrow().device().id();
            helper.assertTrue(joined(world, westTank, southTank), "The tanks are not one island through the racks");

            // D9: a rack carrying a lane stands; one lane comes out alone, its neighbours keep their joints towards it.
            helper.assertTrue(!player.gameMode.destroyBlock(straight) && level.getBlockState(straight).getBlock() instanceof PipeRackBlock && ((PipeRackBlockEntity) level.getBlockEntity(straight)).lanes().size() == 1,
                    "A rack with a lane was broken");
            var cornerRack = (PipeRackBlockEntity) level.getBlockEntity(corner);
            helper.assertTrue(LayingTestCommands.dismantle(player, InteractionHand.MAIN_HAND, onLane(corner, cornerRack, 0)) == null, "The lane dismantle was refused");
            helper.assertTrue(cornerRack.lanes().isEmpty() && world.at(position(level, corner).withLane(options.get(2) + 1)).isEmpty(), "The corner lane remains");
            helper.assertTrue(level.getBlockState(corner).getValue(PipeRackBlock.SHAPE) == PipeRackBlock.Shape.FRONT_RIGHT, "The empty corner lost its shape");
            helper.assertTrue(player.getInventory().countItem(ModItems.FLUID_PIPE.get()) == 2, "The lane's pipe was not refunded");
            var straightRack = (PipeRackBlockEntity) level.getBlockEntity(straight);
            helper.assertTrue(straightRack.lanes().getFirst().joints() == expected[0] && straightRack.drawnLanes().getFirst().joined() == 1 << Direction.WEST.ordinal(),
                    "The lane next to the gap did not become an open end: " + straightRack.drawnLanes());
            helper.assertTrue(straightRack.openFaces(0) == 1 << Direction.EAST.ordinal(), "The lane's open end is not towards the gap");
            helper.assertTrue(!joined(world, westTank, southTank), "The tanks are still joined across the gap");

            // A one-cell lane from one open end to the other: both lanes keep their joints, the gap lane takes the corner.
            var refill = LayingTestCommands.lay(player, InteractionHand.MAIN_HAND, onLane(straight, straightRack, 0), onLane(turned, (PipeRackBlockEntity) level.getBlockEntity(turned), 0), List.of(corner), List.of(options.get(2)));
            helper.assertTrue(refill == null, "The gap was not refilled: " + refill);
            helper.assertTrue(cornerRack.lanes().getFirst().paint() == SKY_BLUE, "The refilled lane is not painted");
            helper.assertTrue(cornerRack.lanes().size() == 1 && cornerRack.lanes().getFirst().joints() == expected[1] && cornerRack.drawnLanes().getFirst().joined() == expected[1],
                    "The refilled corner lane " + cornerRack.drawnLanes());
            helper.assertTrue(joined(world, westTank, southTank), "The tanks are not joined again");
            helper.killAllEntities();
            helper.succeed();
        } finally {
            for (var at : placed) level.removeBlock(at, false);
        }
    }

    /**
     * Owner report 2026-10-27: a one-block pipe laid on a rack showed only after the world was reopened. Its lane has no
     * joint, so nothing the rack draws changed and the copy was never sent. Laid now, the rack is queued in its chunk's
     * block updates, which reach the clients at the end of the tick.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "pipe-laying")
    public static void aOneBlockLaneOnARackIsSentToTheClients(GameTestHelper helper) {
        var level = helper.getLevel();
        var cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(cell, ModBlocks.PIPE_RACK.get().placed(level, cell, Direction.EAST, false), 3);
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "PipeRackOneBlockTest"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(cell.getX() + .5, cell.getY(), cell.getZ() - 1.5);
        var tool = new ItemStack(ModItems.PIPE_LAYING_TOOL.get());
        tool.set(ModItems.PIPE_LAYING_SETTINGS.get(), new PipelineToolItem.Settings(.1));
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        player.getInventory().add(new ItemStack(ModItems.FLUID_PIPE.get(), 1));
        // Two ticks on, the placing update has gone out: whatever is queued after the lay, the lay queued.
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(!queued(level, cell), "The rack's placing update is still queued");
                // Both clicks on one slot of the tier: position 0, near the east face (as in the corner test).
                var click = new PipeAnchor.Click(cell, Direction.UP, new net.minecraft.world.phys.Vec3(.9, .37, .3));
                var refusal = LayingTestCommands.lay(player, InteractionHand.MAIN_HAND, click, click, List.of(cell), List.of(RackGeometry.option(0, RackGeometry.TIER)));
                helper.assertTrue(refusal == null, "The one-block lane was refused: " + refusal);
                var rack = (PipeRackBlockEntity) level.getBlockEntity(cell);
                helper.assertTrue(rack.lanes().size() == 1 && rack.lanes().getFirst().joints() == 0 && rack.lanes().getFirst().arms() == ALONG_X, "The one-block lane: " + rack.lanes());
                helper.assertTrue(queued(level, cell), "The one-block lane was not sent to the clients");
                helper.assertTrue(LayingTestCommands.dismantle(player, InteractionHand.MAIN_HAND, onLane(cell, rack, 0)) == null, "The one-block lane did not come out");
                helper.killAllEntities();
                helper.succeed();
            } finally {
                level.removeBlock(cell, false);
            }
        });
    }

    /**
     * Owner decision 2026-10-27 (batch 2026-10-26-multi-machine-framework, NOZZLE_ROOM_GATE_REVIEW option (C)): a column's
     * nozzle takes a pipe up to the largest its place clears. On a 0.5 m column the reflux's neck is wider than the cladding
     * above 0.25 m: the tool's anchor there refuses a larger line and takes that one, and a pipe in front of the nozzle
     * takes no more. The column comes out whole, its registration with it.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "pipe-laying")
    public static void aColumnNozzleTakesNoLargerPipeThanItsPlaceClears(GameTestHelper helper) {
        var level = helper.getLevel();
        var shape = EquipmentShape.column(.5, new ColumnTrays(3, 2, List.of(), List.of(), List.of()), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(.5));
        var origin = helper.absolutePos(new BlockPos(1, 1, 1));
        EquipmentStructure.place(level, origin, shape, Paint.DEFAULT, Integer.MAX_VALUE);
        var vessel = new EquipmentStructure.Vessel(origin, shape);
        try {
            var port = ShapeRasterizer.ports(shape).stream().filter(p -> shape.nozzles().get(p.nozzle()).role() == EquipmentShape.Role.REFLUX).findFirst().orElseThrow();
            var cell = origin.offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = Direction.fromDelta(port.dx(), port.dy(), port.dz());
            double largest = NozzleBore.largest(shape, port.nozzle());
            helper.assertTrue(Math.abs(largest - .25) < 1e-9, "The 0.5 m column's reflux takes " + largest + " m, not 0.25 m");
            var click = new PipeAnchor.Click(cell, face);
            var refused = PipeAnchor.resolve(level, click, PipeSizes.outside(largest + NozzleBore.STEP));
            helper.assertTrue(refused.anchor() == null && refused.refusal() == PipeAnchor.Refusal.NOZZLE_SIZE, "A larger line at the reflux: " + refused);
            var taken = PipeAnchor.resolve(level, click, PipeSizes.outside(largest));
            helper.assertTrue(taken.anchor() != null && taken.anchor().kind() == PipeAnchor.Kind.NOZZLE, "The reflux's own largest line: " + taken);
            double beside = EquipmentShellBlockEntity.largestBoreBeside(level, cell.relative(face), PhysicalFluidTopology.ALL_FACES);
            helper.assertTrue(Math.abs(beside - largest) < 1e-9, "A pipe in front of the reflux takes " + beside + " m");
        } finally {
            EquipmentTestCleanup.remove(level, vessel.controller());
        }
        helper.runAfterDelay(2, () -> {
            var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
            for (var record : world.registrations().values()) {
                var p = record.device().position();
                helper.assertTrue(p.x() < origin.getX() - 1 || p.x() > origin.getX() + 4 || p.z() < origin.getZ() - 1 || p.z() > origin.getZ() + 4
                        || p.y() < origin.getY() - 1 || p.y() > origin.getY() + 32, "A registration stayed behind at " + p);
            }
            helper.assertTrue(!(level.getBlockEntity(vessel.controller()) instanceof EquipmentShellBlockEntity), "The column stayed");
            helper.succeed();
        });
    }

    /** Whether the block at {@code at} is queued in its chunk's block updates, which go to the clients at the end of the tick. */
    private static boolean queued(net.minecraft.server.level.ServerLevel level, BlockPos at) {
        var holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(net.minecraft.world.level.ChunkPos.asLong(at));
        it.unimi.dsi.fastutil.shorts.ShortSet[] sections = net.neoforged.fml.util.ObfuscationReflectionHelper.getPrivateValue(net.minecraft.server.level.ChunkHolder.class, holder, "changedBlocksPerSection");
        var section = sections[level.getSectionIndex(at.getY())];
        return section != null && section.contains(net.minecraft.core.SectionPos.sectionRelativePos(at));
    }

    /** A click on the middle of lane {@code index}'s first arm. */
    private static PipeAnchor.Click onLane(BlockPos at, PipeRackBlockEntity rack, int index) {
        var box = RackGeometry.boxes(rack.rack(), rack.drawnLanes().get(index)).getFirst();
        return new PipeAnchor.Click(at, Direction.UP, new net.minecraft.world.phys.Vec3((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2));
    }

    /** Whether the two tanks lie in one island once the queued topology applies. */
    private static boolean joined(FluidWorldAuthority world, long a, long b) {
        return world.diagnosticSnapshots().stream().anyMatch(s -> s.graph().reservoirs().stream().anyMatch(n -> n.id() == a) && s.graph().reservoirs().stream().anyMatch(n -> n.id() == b));
    }

    /** A plain line laid with the tool in the main hand: no cell is a rack lane. */
    static net.minecraft.network.chat.Component lay(net.minecraft.server.level.ServerPlayer player, BlockPos start, Direction startFace, BlockPos end, Direction endFace, List<BlockPos> cells) {
        return LayingTestCommands.lay(player, InteractionHand.MAIN_HAND, new PipeAnchor.Click(start, startFace), new PipeAnchor.Click(end, endFace), cells, java.util.Collections.nCopies(cells.size(), -1));
    }

    private static boolean connected(net.minecraft.world.level.Level level, BlockPos at, Direction face) {
        var state = level.getBlockState(at);
        return state.getBlock() instanceof FluidDeviceBlock && state.getValue(FluidDeviceBlock.connection(face));
    }

    private static PhysicalFluidTopology.Position position(net.minecraft.world.level.Level level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }
}
