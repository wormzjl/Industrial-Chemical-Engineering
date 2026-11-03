package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.PipeRoute;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.world.item.PipelineToolItem;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.PipeAnchor;
import com.wormzjl.icheme.world.level.block.PipeLaying;
import com.wormzjl.icheme.world.level.block.PipeRackBlock;
import com.wormzjl.icheme.world.level.block.RegistryRealignment;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Registry first (batch 2026-11-03-save-corruption, REGISTRY_FIRST_PLAN.md section 7): the registry decides which IChemE
 * devices exist, and the world is re-aligned to it. Each test edits the world or the registry directly and removes what it
 * placed, its result held until then.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class RegistryFirstGameTests {
    private RegistryFirstGameTests() {}

    /**
     * Step 3 (decision S11): a held tank keeps what it holds and joins no flow, apart from the line it stood on; the
     * operators' listing names it with what its cell holds; a drop removes it as a broken tank, what it held booked as
     * removed, and refuses a device that is not held.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "registry-first-held")
    public static void aHeldTankJoinsNoFlowIsListedAndDropped(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var west = helper.absolutePos(new BlockPos(1, 1, 1));
        var pipe = west.east();
        var east = west.east(2);
        level.setBlock(west, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        level.setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        level.setBlock(east, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        long westId = world.at(position(level, west)).orElseThrow().device().id();
        long eastId = world.at(position(level, east)).orElseThrow().device().id();
        String[] failure = {null};
        double[] before = {0};
        var said = new ArrayList<String>();
        var operator = operator(level, west, said);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(joined(world, westId, eastId), "Waiting for the line to join its tanks"))
                .thenExecute(() -> {
                    before[0] = moles(world, eastId);
                    if (!world.hold(eastId, true) || world.hold(eastId, true)) failure[0] = "the hold was not queued once";
                })
                .thenWaitUntil(() -> helper.assertTrue(alone(world, eastId), "Waiting for the held tank to stand apart"))
                .thenExecute(() -> {
                    if (failure[0] == null && joined(world, westId, eastId)) failure[0] = "the held tank still joins the line";
                    if (failure[0] == null && Math.abs(moles(world, eastId) - before[0]) > 1e-9 * Math.max(1, before[0]))
                        failure[0] = "the held tank lost what it held: " + before[0] + " -> " + moles(world, eastId);
                    said.clear();
                    level.getServer().getCommands().performPrefixedCommand(operator, "icheme devices held");
                    if (failure[0] == null && said.stream().noneMatch(line -> line.startsWith("#" + eastId + " reservoir at ") && line.contains("its cell holds icheme:")))
                        failure[0] = "the listing does not name the held tank: " + said;
                    said.clear();
                    level.getServer().getCommands().performPrefixedCommand(operator, "icheme devices drop " + westId);
                    if (failure[0] == null && (world.at(position(level, west)).isEmpty() || said.stream().noneMatch(line -> line.contains("is not held"))))
                        failure[0] = "a device that is not held was dropped: " + said;
                    said.clear();
                    level.getServer().getCommands().performPrefixedCommand(operator, "icheme devices drop " + eastId);
                    if (failure[0] == null && said.stream().noneMatch(line -> line.startsWith("Dropped #" + eastId)))
                        failure[0] = "the drop was refused: " + said;
                })
                .thenWaitUntil(() -> helper.assertTrue(!world.registrations().containsKey(eastId) && world.held().stream().noneMatch(r -> r.device().id() == eastId),
                        "Waiting for the dropped tank to leave"))
                .thenExecute(() -> {
                    if (failure[0] == null && !level.getBlockState(east).is(ModBlocks.FLUID_RESERVOIR.get())) failure[0] = "the drop touched the block in its cell";
                    for (var at : List.of(west, pipe, east)) level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.at(position(level, west)).isEmpty() && world.at(position(level, pipe)).isEmpty(), "Waiting for the line's registrations to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /**
     * Step 4 (plan 4.3): every one-block device stands as its registration. Each block, with each fitting, placed alone; the
     * block {@link FluidDeviceBlock#stateFor} gives for what it registered is the block placed.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "registry-first-state")
    public static void everyOneBlockDeviceStandsAsItsRegistration(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var pipe = ModBlocks.FLUID_PIPE.get().defaultBlockState();
        var placed = List.of(
                ModBlocks.FLUID_RESERVOIR.get().defaultBlockState().setValue(FluidDeviceBlock.FACING, Direction.NORTH),
                ModBlocks.FLUID_GENERATOR.get().defaultBlockState().setValue(FluidDeviceBlock.FACING, Direction.EAST),
                ModBlocks.FLUID_VOID.get().defaultBlockState().setValue(FluidDeviceBlock.FACING, Direction.SOUTH),
                ModBlocks.INLINE_FILTER.get().defaultBlockState().setValue(FluidDeviceBlock.FACING, Direction.WEST),
                pipe.setValue(FluidDeviceBlock.FACING, Direction.UP),
                pipe.setValue(FluidDeviceBlock.FACING, Direction.EAST).setValue(FluidDeviceBlock.SHUTOFF, FluidDeviceBlock.Shutoff.OPEN),
                pipe.setValue(FluidDeviceBlock.FACING, Direction.NORTH).setValue(FluidDeviceBlock.SHUTOFF, FluidDeviceBlock.Shutoff.CLOSED),
                pipe.setValue(FluidDeviceBlock.FACING, Direction.DOWN).setValue(FluidDeviceBlock.SHUTOFF, FluidDeviceBlock.Shutoff.CHECK),
                pipe.setValue(FluidDeviceBlock.FACING, Direction.EAST).setValue(FluidDeviceBlock.PRESSURE_CONTROL, true),
                pipe.setValue(FluidDeviceBlock.FACING, Direction.WEST).setValue(FluidDeviceBlock.INLINE_FILTER, true),
                pipe.setValue(FluidDeviceBlock.FACING, Direction.NORTH).setValue(FluidDeviceBlock.INLINE_MOVER, FluidDeviceBlock.InlineMover.PUMP),
                pipe.setValue(FluidDeviceBlock.FACING, Direction.SOUTH).setValue(FluidDeviceBlock.INLINE_MOVER, FluidDeviceBlock.InlineMover.COMPRESSOR));
        var cells = new ArrayList<BlockPos>();
        String[] failure = {null};
        // Two blocks apart, so none connects.
        for (int i = 0; i < placed.size(); i++) {
            var at = helper.absolutePos(new BlockPos(1 + 2 * (i % 4), 1 + 2 * (i / 4), 1));
            cells.add(at);
            level.setBlock(at, placed.get(i), 3);
            var stood = FluidDeviceBlock.stateFor(world.at(position(level, at)).orElseThrow().device());
            if (failure[0] == null && !placed.get(i).equals(stood)) failure[0] = "placed " + placed.get(i) + ", stands as " + stood;
        }
        for (var at : cells) level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(cells.stream().allMatch(at -> world.at(position(level, at)).isEmpty()), "Waiting for the blocks' registrations to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /**
     * Step 4, GameTests a to c (plan section 7): blocks lost behind the authority's back, as in a chunk saved after the
     * fluid store, are set back from the registry. An intact line re-aligns nothing. Then a pipe gone (a), the charged tank
     * gone (b) and a filter where a pipe stood (c): each block comes back with its identity, the tank with its charge, the
     * filter's block takes no pipe's registration, and nothing drops.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "registry-first-realign-line")
    public static void aBrokenLineIsSetBackFromTheRegistry(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var west = helper.absolutePos(new BlockPos(1, 1, 1));
        var first = west.east();
        var second = west.east(2);
        var east = west.east(3);
        var cells = List.of(west, first, second, east);
        level.setBlock(west, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        level.setBlock(first, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        level.setBlock(second, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        level.setBlock(east, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        long[] ids = cells.stream().mapToLong(at -> world.at(position(level, at)).orElseThrow().device().id()).toArray();
        var charge = new TankCharge(helper, world, east, Direction.SOUTH, 200000);
        String[] failure = {null};
        double[] before = {0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(charge.charged() && joined(world, ids[0], ids[3]), "Waiting for the charged line"))
                .thenExecute(() -> {
                    for (long id : ids) {
                        var check = RegistryRealignment.realign(level, world, id);
                        if (failure[0] == null && (check.outcome() != RegistryRealignment.Outcome.NOTHING || check.released())) failure[0] = "the intact line was re-aligned at #" + id + ": " + check;
                    }
                    before[0] = moles(world, ids[3]);
                    FluidDeviceBlock.quietly(() -> {
                        level.setBlock(first, Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(east, Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(second, ModBlocks.INLINE_FILTER.get().defaultBlockState().setValue(FluidDeviceBlock.FACING, Direction.EAST), 3);
                    });
                    for (int i = 0; i < ids.length && failure[0] == null; i++)
                        if (world.at(position(level, cells.get(i))).map(r -> r.device().id()).orElse(0L) != ids[i]) failure[0] = "a block edited quietly touched the registry at " + cells.get(i).toShortString();
                    var filter = (FluidDeviceBlockEntity) level.getBlockEntity(second);
                    if (failure[0] == null && filter.bind(world, world.registrations().get(ids[2]).device())) failure[0] = "a filter block bound a pipe's registration";
                    try {
                        filter.bindIdentity(ids[2]);
                        if (failure[0] == null) failure[0] = "a filter block took a pipe's identity";
                    } catch (IllegalStateException expected) {
                        // The kind check.
                    }
                    requeue(world, level, cells);
                })
                .thenWaitUntil(() -> helper.assertTrue(stands(level, first, ids[1]) && stands(level, second, ids[2]) && stands(level, east, ids[3]), "Waiting for the line to be set back"))
                .thenExecute(() -> {
                    if (failure[0] == null && !level.getBlockState(second).is(ModBlocks.FLUID_PIPE.get())) failure[0] = "the filter block was not set back to a pipe";
                    if (failure[0] == null && !level.getBlockState(east).is(ModBlocks.FLUID_RESERVOIR.get())) failure[0] = "the tank was not set back";
                    for (int i = 0; i < ids.length && failure[0] == null; i++)
                        if (world.at(position(level, cells.get(i))).map(r -> r.device().id()).orElse(0L) != ids[i]) failure[0] = "the line's registrations changed at " + cells.get(i).toShortString();
                    if (failure[0] == null && !(moles(world, ids[3]) > .99 * before[0])) failure[0] = "the tank lost its charge: " + before[0] + " -> " + moles(world, ids[3]);
                    if (failure[0] == null && !level.getEntitiesOfClass(ItemEntity.class, new AABB(west).inflate(6)).isEmpty()) failure[0] = "a replaced block dropped an item";
                    for (int i = 0; i + 1 < cells.size() && failure[0] == null; i++)
                        if (!level.getBlockState(cells.get(i)).getValue(FluidDeviceBlock.connection(Direction.EAST)) || !level.getBlockState(cells.get(i + 1)).getValue(FluidDeviceBlock.connection(Direction.WEST)))
                            failure[0] = "the line is not connected between " + cells.get(i).toShortString() + " and " + cells.get(i + 1).toShortString();
                    for (var at : cells) level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> helper.assertTrue(cells.stream().allMatch(at -> world.at(position(level, at)).isEmpty()), "Waiting for the line's registrations to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /**
     * Step 4, GameTest d (decision S11): a pipe whose cell holds stone is held and listed, never the stone overwritten, and
     * its tanks part. Once the stone is gone and the chunk loads again, the pipe stands, is released, and joins them again.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "registry-first-realign-blocked")
    public static void aBlockedCellHoldsItsPipeUntilItIsFree(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var west = helper.absolutePos(new BlockPos(1, 1, 1));
        var pipe = west.east();
        var east = west.east(2);
        var cells = List.of(west, pipe, east);
        level.setBlock(west, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        level.setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        level.setBlock(east, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        long westId = world.at(position(level, west)).orElseThrow().device().id();
        long pipeId = world.at(position(level, pipe)).orElseThrow().device().id();
        long eastId = world.at(position(level, east)).orElseThrow().device().id();
        String[] failure = {null};
        var said = new ArrayList<String>();
        var operator = operator(level, west, said);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(joined(world, westId, eastId), "Waiting for the line to join its tanks"))
                .thenExecute(() -> {
                    FluidDeviceBlock.quietly(() -> level.setBlock(pipe, Blocks.STONE.defaultBlockState(), 3));
                    requeue(world, level, cells);
                })
                .thenWaitUntil(() -> helper.assertTrue(held(world, pipeId) && !joined(world, westId, eastId), "Waiting for the blocked pipe to be held"))
                .thenExecute(() -> {
                    if (failure[0] == null && !level.getBlockState(pipe).is(Blocks.STONE)) failure[0] = "the stone was overwritten";
                    said.clear();
                    level.getServer().getCommands().performPrefixedCommand(operator, "icheme devices held");
                    if (failure[0] == null && said.stream().noneMatch(line -> line.startsWith("#" + pipeId + " pipe at ") && line.endsWith("its cell holds minecraft:stone")))
                        failure[0] = "the listing does not name the blocked pipe: " + said;
                    level.setBlock(pipe, Blocks.AIR.defaultBlockState(), 3);
                    requeue(world, level, cells);
                })
                .thenWaitUntil(() -> helper.assertTrue(stands(level, pipe, pipeId) && !held(world, pipeId) && joined(world, westId, eastId), "Waiting for the pipe to stand and join its tanks again"))
                .thenExecute(() -> {
                    for (var at : cells) level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> helper.assertTrue(cells.stream().allMatch(at -> world.at(position(level, at)).isEmpty()), "Waiting for the line's registrations to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /**
     * Step 5, GameTest e (plan 4.2 item 3): rack cells changed behind the authority's back are set back from the registry.
     * A line laid through three racks, the middle one a corner: each lane holds its cell's frame, and an intact run
     * re-aligns nothing. Then the corner cell is gone and the first rack faces the other way: the corner comes back as the
     * corner it was, the first rack faces as it was laid, each with its lane and the joins it had, and the tanks stay one
     * island. Once the line is gone, so are its frames.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "registry-first-realign-rack")
    public static void aLostRackCellIsSetBackWithItsLanes(GameTestHelper helper) {
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
        var cells = List.of(straight, corner, turned);
        for (var at : placed) level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(west, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        level.setBlock(south, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        // As the laying test places them: one looking east, a corner at its end looking south, one more south of it.
        var racks = ModBlocks.PIPE_RACK.get();
        level.setBlock(straight, racks.placed(level, straight, Direction.EAST, false), 3);
        level.setBlock(corner, racks.placed(level, corner, Direction.SOUTH, false), 3);
        level.setBlock(turned, racks.placed(level, turned, Direction.SOUTH, false), 3);
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "RegistryFirstRack"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(west.getX() + 2.5, west.getY(), west.getZ() - 1.5);
        var tool = new ItemStack(ModItems.PIPE_LAYING_TOOL.get());
        tool.set(ModItems.PIPE_LAYING_SETTINGS.get(), new PipelineToolItem.Settings(.1));
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        player.getInventory().add(new ItemStack(ModItems.FLUID_PIPE.get(), 5));
        var start = new PipeAnchor.Click(west, Direction.EAST);
        var end = new PipeAnchor.Click(south, Direction.NORTH);
        double outside = PipeSizes.outside(.1);
        String[] failure = {null};
        var from = PipeAnchor.resolve(level, start, outside);
        var to = PipeAnchor.resolve(level, end, outside);
        if (from.anchor() == null || to.anchor() == null) failure[0] = "the line's ends were refused: " + from.refusal() + ", " + to.refusal();
        else {
            var route = PipeRoute.search(from.anchor().end(), from.anchor().leaving(), to.anchor().end(), outside, PipeAnchor.space(level), PipeRoute.Limits.DEFAULT);
            var refusal = route.found() ? PipeLaying.lay(player, InteractionHand.MAIN_HAND, start, end, List.of(first, straight, corner, turned, last), route.options()) : null;
            if (!route.found()) failure[0] = "no route through the racks: " + route;
            else if (refusal != null) failure[0] = "the line was refused: " + refusal.getString();
        }
        long[] lanes = new long[cells.size()];
        var frames = new ArrayList<WorldTopologyLedger.RackFrame>();
        for (int i = 0; i < cells.size() && failure[0] == null; i++) {
            lanes[i] = ((PipeRackBlockEntity) level.getBlockEntity(cells.get(i))).lanes().getFirst().identity();
            frames.add(PipeRackBlock.frame(level.getBlockState(cells.get(i))));
            if (!frames.get(i).equals(world.rackFrame(lanes[i]))) failure[0] = "lane #" + lanes[i] + " holds no frame of its rack: " + world.rackFrame(lanes[i]);
        }
        int[] joins = {1 << Direction.WEST.ordinal() | 1 << Direction.EAST.ordinal(), 1 << Direction.WEST.ordinal() | 1 << Direction.SOUTH.ordinal(),
                1 << Direction.NORTH.ordinal() | 1 << Direction.SOUTH.ordinal()};
        long westTank = world.at(position(level, west)).orElseThrow().device().id();
        long southTank = world.at(position(level, south)).orElseThrow().device().id();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || joined(world, westTank, southTank), "Waiting for the line to join its tanks"))
                .thenExecute(() -> {
                    if (failure[0] != null) return;
                    for (long id : lanes) {
                        var check = RegistryRealignment.realign(level, world, id);
                        if (failure[0] == null && (check.outcome() != RegistryRealignment.Outcome.NOTHING || check.released())) failure[0] = "the intact run was re-aligned at #" + id + ": " + check;
                    }
                    FluidDeviceBlock.quietly(() -> {
                        level.setBlock(corner, Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(straight, level.getBlockState(straight).setValue(PipeRackBlock.FACING, Direction.WEST), 3);
                    });
                    for (long id : lanes)
                        if (failure[0] == null && !world.registrations().containsKey(id)) failure[0] = "a rack edited quietly removed lane #" + id;
                    requeue(world, level, placed);
                })
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || java.util.stream.IntStream.range(0, cells.size()).allMatch(i -> level.getBlockEntity(cells.get(i)) instanceof PipeRackBlockEntity rack
                        && rack.carries(lanes[i]) && PipeRackBlock.frame(level.getBlockState(cells.get(i))).equals(frames.get(i))), "Waiting for the rack cells to be set back"))
                .thenExecute(() -> {
                    for (int i = 0; i < cells.size() && failure[0] == null; i++) {
                        var rack = (PipeRackBlockEntity) level.getBlockEntity(cells.get(i));
                        if (rack.lanes().size() != 1 || rack.drawnLanes().getFirst().joined() != joins[i]) failure[0] = "rack " + cells.get(i).toShortString() + " draws " + rack.drawnLanes();
                    }
                    if (failure[0] == null && !joined(world, westTank, southTank)) failure[0] = "the tanks parted";
                    if (failure[0] == null && !level.getEntitiesOfClass(ItemEntity.class, new AABB(west).inflate(6)).isEmpty()) failure[0] = "a rack cell dropped an item";
                    for (var at : placed) level.removeBlock(at, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(Arrays.stream(lanes).allMatch(id -> world.committedDevice(id).isEmpty())
                        && placed.stream().allMatch(at -> world.at(position(level, at)).isEmpty()), "Waiting for the lanes' removals to apply"))
                .thenExecute(() -> {
                    // The frames leave as the removals apply, as a line's paint does.
                    for (long id : lanes)
                        if (failure[0] == null && world.rackFrame(id) != null) failure[0] = "lane #" + id + "'s frame stayed after the lane left";
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /**
     * Step 6, GameTest f (plan 4.4): a drum holding nitrogen whose cells were removed behind the authority's back is
     * rebuilt from its structure record, with its identity, its paint and what it held. A cell another block holds is left
     * out and the drum held; once the cell is free and the chunk loads again, the cell is placed and the drum released.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "registry-first-realign-drum")
    public static void aLostDrumIsRebuiltFromItsRecordWithWhatItHeld(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var shape = EquipmentShape.drum(3, 3, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 0);
        var origin = helper.absolutePos(new BlockPos(1, 1, 1));
        var layout = ShapeRasterizer.layout(shape);
        var cells = cells(origin, layout);
        var node = cell(origin, ShapeRasterizer.fluidCell(shape));
        var ports = ShapeRasterizer.ports(shape);
        var nozzle = ports.stream().max(Comparator.comparingInt(ShapeRasterizer.Port::dy)).orElseThrow();
        var face = Direction.fromDelta(nozzle.dx(), nozzle.dy(), nozzle.dz());
        var nozzles = new HashSet<ShapeRasterizer.Cell>();
        for (var port : ports) nozzles.add(port.cell());
        var blocked = cell(origin, layout.shell().stream().filter(c -> !c.equals(ShapeRasterizer.fluidCell(shape)) && !nozzles.contains(c)).findFirst().orElseThrow());
        var paint = new Paint(0x587246, 0x383E42);
        EquipmentStructure.place(level, origin, shape, paint, Integer.MAX_VALUE);
        long id = world.at(position(level, node)).orElseThrow().device().id();
        // Some nitrogen in the drum, through its top nozzle: a generator one pipe above it, until the drum holds 10 mol (a
        // full charge of its 28 m³ takes thousands of ticks through one pipe).
        var pipe = cell(origin, nozzle.cell()).relative(face);
        var generator = pipe.relative(face);
        level.setBlock(generator, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), 3);
        var nitrogen = FluidDeviceSpec.nitrogen(com.wormzjl.icheme.science.material.MaterialRuntime.active()).composition();
        TankCharge.run(world, ((FluidDeviceBlockEntity) level.getBlockEntity(generator)).fluidIdentity(), new FluidDeviceSpec(1, 298.15, 200000, nitrogen));
        level.setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        String[] failure = {null};
        double[] before = {0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(moles(world, id) > 10, "Waiting for nitrogen in the drum"))
                .thenExecute(() -> {
                    level.removeBlock(generator, false);
                    level.removeBlock(pipe, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.capture().world().events().isEmpty() && alone(world, id), "Waiting for the drum to stand alone"))
                .thenExecute(() -> {
                    before[0] = moles(world, id);
                    FluidDeviceBlock.quietly(() -> {
                        for (var at : cells) level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                    });
                    if (world.at(position(level, node)).map(r -> r.device().id()).orElse(0L) != id || world.structure(position(level, origin)) == null)
                        failure[0] = "cells removed quietly touched the registry";
                    level.setBlock(blocked, Blocks.STONE.defaultBlockState(), 3);
                    requeue(world, level, cells);
                })
                .thenWaitUntil(() -> helper.assertTrue(held(world, id) && shellStands(level, node, id), "Waiting for the drum to be rebuilt round the stone and held"))
                .thenExecute(() -> {
                    if (failure[0] == null && !level.getBlockState(blocked).is(Blocks.STONE)) failure[0] = "the stone in a cell of the drum was overwritten";
                    level.setBlock(blocked, Blocks.AIR.defaultBlockState(), 3);
                    requeue(world, level, cells);
                })
                .thenWaitUntil(() -> helper.assertTrue(!held(world, id) && standsAs(level, origin, shape) && !Double.isNaN(moles(world, id)), "Waiting for the drum to be whole and released"))
                .thenExecute(() -> {
                    if (failure[0] == null && world.at(position(level, node)).map(r -> r.device().id()).orElse(0L) != id) failure[0] = "the drum's registration changed";
                    if (failure[0] == null && !(moles(world, id) > .99 * before[0])) failure[0] = "the drum lost what it held: " + before[0] + " -> " + moles(world, id);
                    for (var c : layout.shell()) {
                        var shell = (EquipmentShellBlockEntity) level.getBlockEntity(cell(origin, c));
                        if (failure[0] == null && (shell.stale() || !shell.paint().equals(paint))) failure[0] = "cell " + c + " was left stale or lost its paint";
                    }
                    if (failure[0] == null && !level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(8)).isEmpty()) failure[0] = "the rebuild dropped an item";
                    level.removeBlock(node, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.at(position(level, node)).isEmpty() && world.structure(position(level, origin)) == null, "Waiting for the drum and its record to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /**
     * Step 6, GameTest g (plan 4.4): a drum whose cells a taller drum of the same origin holds (one built there after the
     * registry's last save) is rebuilt to its record: the cells only the taller drum had are cleared, every shell cell takes
     * the record's shape and paint, and the drum keeps its registration.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "registry-first-realign-shape")
    public static void aDrumOfAnotherShapeIsRebuiltToItsRecord(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var recorded = EquipmentShape.drum(3, 3, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 0);
        var built = EquipmentShape.drum(3, 5, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 0);
        var origin = helper.absolutePos(new BlockPos(1, 1, 1));
        var paint = new Paint(0xE25303, 0xF7B500);
        var other = new Paint(0x587246, 0x383E42);
        EquipmentStructure.place(level, origin, recorded, paint, Integer.MAX_VALUE);
        var node = cell(origin, ShapeRasterizer.fluidCell(recorded));
        long id = world.at(position(level, node)).orElseThrow().device().id();
        var recordCells = cells(origin, ShapeRasterizer.layout(recorded));
        var builtLayout = ShapeRasterizer.layout(built);
        var builtCells = cells(origin, builtLayout);
        var every = new HashSet<>(recordCells);
        every.addAll(builtCells);
        String[] failure = {null};
        FluidDeviceBlock.quietly(() -> {
            for (var at : recordCells) level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
            for (var c : builtLayout.shell()) {
                var at = cell(origin, c);
                level.setBlock(at, ModBlocks.EQUIPMENT_SHELL.get().defaultBlockState(), 3);
                ((EquipmentShellBlockEntity) level.getBlockEntity(at)).bind(built, c, other);
            }
            for (var c : builtLayout.interior()) level.setBlock(cell(origin, c), ModBlocks.EQUIPMENT_INTERIOR.get().defaultBlockState(), 3);
        });
        var record = world.structure(position(level, origin));
        if (record == null || !record.shape().equals(recorded) || world.at(position(level, node)).map(r -> r.device().id()).orElse(0L) != id)
            failure[0] = "cells replaced quietly touched the registry";
        requeue(world, level, builtCells);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standsAs(level, origin, recorded) && shellStands(level, node, id), "Waiting for the drum to be rebuilt to its record"))
                .thenExecute(() -> {
                    var kept = new HashSet<>(recordCells);
                    for (var at : builtCells)
                        if (failure[0] == null && !kept.contains(at) && !level.getBlockState(at).isAir()) failure[0] = "a cell only the taller drum had stands at " + at.toShortString();
                    for (var c : ShapeRasterizer.layout(recorded).shell())
                        if (failure[0] == null && !((EquipmentShellBlockEntity) level.getBlockEntity(cell(origin, c))).paint().equals(paint)) failure[0] = "cell " + c + " did not take the record's paint";
                    if (failure[0] == null && world.at(position(level, node)).map(r -> r.device().id()).orElse(0L) != id) failure[0] = "the drum's registration changed";
                    if (failure[0] == null && held(world, id)) failure[0] = "the whole drum is held";
                    level.removeBlock(node, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.at(position(level, node)).isEmpty() && world.structure(position(level, origin)) == null, "Waiting for the drum and its record to leave"))
                .thenExecute(() -> {
                    for (var at : every) if (failure[0] == null && !level.getBlockState(at).isAir()) failure[0] = "a ghost block at " + at.toShortString();
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /** The cells of {@code layout} at {@code origin}, shell first. */
    private static List<BlockPos> cells(BlockPos origin, ShapeRasterizer.Layout layout) {
        var cells = new ArrayList<BlockPos>();
        for (var list : List.of(layout.shell(), layout.interior()))
            for (var c : list) cells.add(cell(origin, c));
        return cells;
    }

    private static BlockPos cell(BlockPos origin, ShapeRasterizer.Cell c) {
        return origin.offset(c.x(), c.y(), c.z());
    }

    /** Whether every cell of {@code shape}'s layout at {@code origin} stands as its own: a shell cell of that origin and shape, an interior cell. */
    private static boolean standsAs(ServerLevel level, BlockPos origin, EquipmentShape shape) {
        var layout = ShapeRasterizer.layout(shape);
        for (var c : layout.shell())
            if (!(level.getBlockEntity(cell(origin, c)) instanceof EquipmentShellBlockEntity shell) || !origin.equals(shell.origin()) || !shape.equals(shell.shape())) return false;
        for (var c : layout.interior()) if (!level.getBlockState(cell(origin, c)).is(ModBlocks.EQUIPMENT_INTERIOR.get())) return false;
        return true;
    }

    /** Whether the shell cell at {@code at} stands for registration {@code id}. */
    private static boolean shellStands(ServerLevel level, BlockPos at, long id) {
        return level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell && shell.fluidIdentity() == id;
    }

    /** Queues the registrations of the chunks of {@code cells} again, as their chunks' next load does. */
    private static void requeue(FluidWorldAuthority world, ServerLevel level, List<BlockPos> cells) {
        cells.stream().map(at -> new ChunkPos(at).toLong()).distinct().forEach(chunk -> world.loadedChunk(level.dimension(), chunk));
    }

    /** Whether the block at {@code at} is bound to registration {@code id}. */
    private static boolean stands(ServerLevel level, BlockPos at, long id) {
        return level.getBlockEntity(at) instanceof FluidDeviceBlockEntity entity && entity.fluidIdentity() == id;
    }

    private static boolean held(FluidWorldAuthority world, long id) {
        var record = world.registrations().get(id);
        return record != null && record.device().held();
    }

    private static PhysicalFluidTopology.Position position(ServerLevel level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    private static boolean joined(FluidWorldAuthority world, long a, long b) {
        return world.diagnosticSnapshots().stream().anyMatch(s -> s.graph().reservoirs().stream().anyMatch(n -> n.id() == a) && s.graph().reservoirs().stream().anyMatch(n -> n.id() == b));
    }

    /** Whether {@code tank} is an island of its own: no pipe, no other vessel. */
    private static boolean alone(FluidWorldAuthority world, long tank) {
        return world.diagnosticSnapshots().stream().anyMatch(s -> s.graph().pipes().isEmpty() && !s.graph().reservoirs().isEmpty() && s.graph().reservoirs().stream().allMatch(n -> n.id() == tank));
    }

    private static double moles(FluidWorldAuthority world, long tank) {
        for (var s : world.diagnosticSnapshots())
            for (var n : s.graph().reservoirs())
                if (n.id() == tank && n.kind() == PassiveNetwork.NodeKind.RESERVOIR) return Arrays.stream(n.inventory().moles()).sum();
        return Double.NaN;
    }

    /** An operator at {@code at} whose command replies land in {@code said}. */
    private static CommandSourceStack operator(ServerLevel level, BlockPos at, List<String> said) {
        var sink = new CommandSource() {
            @Override public void sendSystemMessage(Component message) {said.add(message.getString());}
            @Override public boolean acceptsSuccess() {return true;}
            @Override public boolean acceptsFailure() {return true;}
            @Override public boolean shouldInformAdmins() {return false;}
        };
        return new CommandSourceStack(sink, Vec3.atCenterOf(at), Vec2.ZERO, level, 4, "registry-first", Component.literal("registry-first"), level.getServer(), null);
    }
}
